package com.bugtrace.backend;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Syntax-only indexing: never compiles source or executes build plugins. */
public final class JavaSymbolIndexer {
    public static final String VERSION="3.28.2";
    public record Symbol(String kind,String name,String owner,String signature,String filePath,
        int startLine,int startColumn,int endLine,int endColumn,List<String> annotations) {}
    public record Failure(String phase,String message,Integer line,Integer column) {}
    public record SourceFile(String filePath,String packageName,boolean parsed,
        List<Symbol> symbols,List<Failure> failures) {}
    public record Index(String parser,String parserVersion,String languageLevel,String status,
        Instant indexedAt,int javaFiles,int parsedFiles,int failedFiles,int symbolCount,
        List<SourceFile> files) {}

    public Index index(Path base) throws IOException {
        base=base.toAbsolutePath().normalize();
        if (!base.toRealPath().equals(base)) throw new IOException("Index base must be a real directory");
        List<Path> paths;
        try (var stream=Files.walk(base)) {
            paths=stream.limit(2002).sorted().toList();
        }
        if (paths.size()>2001) throw new IOException("Snapshot exceeds the initial index entry limit");
        List<SourceFile> files=new ArrayList<>();
        for (Path path:paths) {
            if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
                throw new IOException("Linked paths are not supported");
            if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular snapshot file");
            String relative=base.relativize(path).toString().replace('\\','/');
            if (!relative.endsWith(".java")) continue;
            if (files.size()>=1000) throw new IOException("Too many Java source files");
            try {
                if (Files.size(path)>5L*1024*1024) throw new IOException("Java source exceeds 5 MiB");
                files.add(parse(relative,Files.readString(path,StandardCharsets.UTF_8)));
                if (files.stream().mapToInt(file->file.symbols().size()).sum()>100000)
                    throw new IllegalArgumentException("Index exceeds 100000 symbols; use a smaller module");
            } catch (IOException exception) {
                files.add(new SourceFile(relative,"",false,List.of(),List.of(new Failure("READ",exception.getMessage(),null,null))));
            }
        }
        int parsed=(int)files.stream().filter(SourceFile::parsed).count();
        int symbols=files.stream().mapToInt(file->file.symbols().size()).sum();
        return new Index("JavaParser",VERSION,"JAVA_21",files.isEmpty()?"EMPTY":parsed==files.size()?"COMPLETE":"PARTIAL",
            Instant.now(),files.size(),parsed,files.size()-parsed,symbols,List.copyOf(files));
    }

    public SourceFile parse(String path,String source) {
        try {
            var config=new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
            var result=new JavaParser(config).parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                List<Failure> failures=new ArrayList<>();
                for (Problem problem:result.getProblems()) {
                    var range=problem.getLocation().flatMap(TokenRange::toRange);
                    failures.add(new Failure("PARSE",shorten(problem.getMessage()),
                        range.map(value->value.begin.line).orElse(null),range.map(value->value.begin.column).orElse(null)));
                }
                if (failures.isEmpty()) failures.add(new Failure("PARSE","Parser returned no compilation unit",null,null));
                return new SourceFile(path,"",false,List.of(),List.copyOf(failures));
            }
            CompilationUnit unit=result.getResult().orElseThrow();
            String packageName=unit.getPackageDeclaration().map(value->value.getNameAsString()).orElse("");
            List<Symbol> symbols=new ArrayList<>();
            unit.getPackageDeclaration().ifPresent(node->add(symbols,node,"PACKAGE",node.getNameAsString(),"",node.getNameAsString(),path));
            unit.getImports().forEach(node->add(symbols,node,"IMPORT",node.getNameAsString(),packageName,
                (node.isStatic()?"static ":"")+node.getNameAsString()+(node.isAsterisk()?".*":""),path));
            unit.walk(node->{
                String owner=owner(node,packageName);
                if (node instanceof ClassOrInterfaceDeclaration declaration)
                    add(symbols,node,declaration.isInterface()?"INTERFACE":"CLASS",declaration.getNameAsString(),owner,declaration.getNameAsString(),path);
                else if (node instanceof RecordDeclaration declaration) {
                    add(symbols,node,"RECORD",declaration.getNameAsString(),owner,declaration.getNameAsString(),path);
                    declaration.getParameters().forEach(parameter->add(symbols,parameter,"RECORD_COMPONENT",parameter.getNameAsString(),
                        owner+(owner.isEmpty()?"":".")+declaration.getNameAsString(),parameter.getTypeAsString()+" "+parameter.getNameAsString(),path));
                } else if (node instanceof EnumDeclaration declaration)
                    add(symbols,node,"ENUM",declaration.getNameAsString(),owner,declaration.getNameAsString(),path);
                else if (node instanceof AnnotationDeclaration declaration)
                    add(symbols,node,"ANNOTATION_TYPE",declaration.getNameAsString(),owner,declaration.getNameAsString(),path);
                else if (node instanceof MethodDeclaration declaration)
                    add(symbols,node,"METHOD",declaration.getNameAsString(),owner,declaration.getDeclarationAsString(false,false,false),path);
                else if (node instanceof ConstructorDeclaration declaration)
                    add(symbols,node,"CONSTRUCTOR",declaration.getNameAsString(),owner,declaration.getDeclarationAsString(false,false,false),path);
                else if (node instanceof CompactConstructorDeclaration declaration)
                    add(symbols,node,"CONSTRUCTOR",declaration.getNameAsString(),owner,declaration.getNameAsString()+" {compact}",path);
                else if (node instanceof AnnotationMemberDeclaration declaration)
                    add(symbols,node,"ANNOTATION_MEMBER",declaration.getNameAsString(),owner,declaration.getTypeAsString()+" "+declaration.getNameAsString()+"()",path);
                else if (node instanceof EnumConstantDeclaration declaration)
                    add(symbols,node,"ENUM_CONSTANT",declaration.getNameAsString(),owner,declaration.getNameAsString(),path);
                else if (node instanceof FieldDeclaration declaration)
                    declaration.getVariables().forEach(variable->{
                        List<String> annotations=declaration.getAnnotations().stream().map(value->value.getNameAsString()).toList();
                        add(symbols,variable,"FIELD",variable.getNameAsString(),owner,variable.getTypeAsString()+" "+variable.getNameAsString(),path,annotations);
                    });
                else if (node instanceof AnnotationExpr annotation)
                    add(symbols,node,"ANNOTATION",annotation.getNameAsString(),owner,annotation.getNameAsString(),path);
            });
            symbols.sort(Comparator.comparingInt(Symbol::startLine).thenComparingInt(Symbol::startColumn).thenComparing(Symbol::kind));
            return new SourceFile(path,packageName,true,List.copyOf(symbols),List.of());
        } catch (RuntimeException exception) {
            return new SourceFile(path,"",false,List.of(),List.of(new Failure("PARSE",shorten(exception.toString()),null,null)));
        }
    }
    private String owner(Node node,String packageName) {
        List<String> names=new ArrayList<>(); Node parent=node.getParentNode().orElse(null);
        while (parent!=null) {
            if (parent instanceof TypeDeclaration<?> type) names.addFirst(type.getNameAsString());
            parent=parent.getParentNode().orElse(null);
        }
        return (packageName.isEmpty()?"":packageName+(names.isEmpty()?"":"."))+String.join(".",names);
    }
    private void add(List<Symbol> symbols,Node node,String kind,String name,String owner,String signature,String path) {
        List<String> annotations=node instanceof NodeWithAnnotations<?> annotated ?
            annotated.getAnnotations().stream().map(value->value.getNameAsString()).toList():List.of();
        add(symbols,node,kind,name,owner,signature,path,annotations);
    }
    private void add(List<Symbol> symbols,Node node,String kind,String name,String owner,String signature,String path,List<String> annotations) {
        var range=node.getRange().orElseThrow();
        if (symbols.size()>=100000) throw new IllegalArgumentException("Too many symbols in one Java file");
        symbols.add(new Symbol(kind,name,owner,shorten(signature),path,range.begin.line,range.begin.column,range.end.line,range.end.column,annotations));
    }
    private String shorten(String text) { return text.length()>2000?text.substring(0,2000)+" [truncated]":text; }
}
