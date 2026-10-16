package com.bugtrace.backend;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Conservative source relationships, not compiler binding or runtime coverage. */
public final class JavaRelationshipAnalyzer {
    public static final String VERSION="1";
    public record Vertex(String id,String kind,String label,String filePath,int startLine,int endLine) {}
    public record Edge(String id,String kind,String from,String to,String confidence,String reason,
        String filePath,int line,int column) {}
    public record Graph(String algorithmVersion,String parserVersion,String status,Instant builtAt,
        int javaFiles,int failedFiles,int uncertainEdges,List<Vertex> nodes,List<Edge> edges,
        List<JavaSymbolIndexer.SourceFile> diagnostics,List<String> limitations) {}
    private record Unit(String path,CompilationUnit ast) {}
    private record TypeInfo(Unit unit,TypeDeclaration<?> ast,String name,String id,List<String> parents) {}
    private record MethodInfo(TypeInfo owner,MethodDeclaration ast,String id) {}
    private final Map<String,Vertex> nodes=new LinkedHashMap<>();
    private final List<Edge> edges=new ArrayList<>();
    private final Map<String,List<TypeInfo>> types=new LinkedHashMap<>();
    private final IdentityHashMap<Node,String> declarations=new IdentityHashMap<>();
    private final List<MethodInfo> methods=new ArrayList<>();
    private int traversalWork;

    public Graph analyze(Path base) throws Exception {
        nodes.clear();edges.clear();types.clear();declarations.clear();methods.clear();traversalWork=0;
        base=base.toAbsolutePath().normalize();
        var index=new JavaSymbolIndexer().index(base);
        List<Unit> units=new ArrayList<>();
        for (var file:index.files()) {
            if (!file.parsed()) continue;
            var parsed=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(Files.readString(base.resolve(file.filePath())));
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) throw new IllegalStateException("Source changed or reparsing failed: "+file.filePath());
            units.add(new Unit(file.filePath(),parsed.getResult().orElseThrow()));
        }
        for (Unit unit:units) {
            vertex("file:"+unit.path,"FILE",unit.path,unit.path,1,unit.ast.getRange().map(range->range.end.line).orElse(1));
            unit.ast.walk(node->{
                if (node instanceof TypeDeclaration<?> type) {
                    String id=id(unit,node,"TYPE"),name=qualified(type,unit);
                    String kind=type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()?"INTERFACE":
                        type instanceof RecordDeclaration?"RECORD":type instanceof EnumDeclaration?"ENUM":type instanceof AnnotationDeclaration?"ANNOTATION_TYPE":"CLASS";
                    vertex(id,kind,name,unit.path,line(node),end(node));declarations.put(node,id);
                    types.computeIfAbsent(name,key->new ArrayList<>()).add(new TypeInfo(unit,type,name,id,new ArrayList<>()));
                }
            });
        }
        for (var group:types.values()) for (TypeInfo type:group) {
            edge("DECLARES","file:"+type.unit.path,type.id,"DECLARED","Type declaration in this file",type.unit,type.ast);
            type.ast.walk(node->{
                if (nearestType(node)!=type.ast || anonymousContext(node)) return;
                if (node instanceof MethodDeclaration method) {
                    String id=id(type.unit,node,"METHOD");declarations.put(node,id);
                    vertex(id,"METHOD",type.name+"."+method.getSignature().asString(),type.unit.path,line(node),end(node));
                    methods.add(new MethodInfo(type,method,id));
                    if (methods.size()>2000) throw new IllegalArgumentException("Initial relationship analysis supports at most 2000 methods");
                    edge("DECLARES",type.id,id,"DECLARED","Method declaration",type.unit,node);
                } else if (node instanceof ConstructorDeclaration constructor) {
                    String id=id(type.unit,node,"CONSTRUCTOR");declarations.put(node,id);
                    vertex(id,"CONSTRUCTOR",type.name+"."+constructor.getDeclarationAsString(false,false,false),type.unit.path,line(node),end(node));
                    edge("DECLARES",type.id,id,"DECLARED","Constructor declaration",type.unit,node);
                } else if (node instanceof CompactConstructorDeclaration constructor) {
                    String id=id(type.unit,node,"CONSTRUCTOR");declarations.put(node,id);
                    vertex(id,"CONSTRUCTOR",type.name+"."+constructor.getNameAsString()+" {compact}",type.unit.path,line(node),end(node));
                    edge("DECLARES",type.id,id,"DECLARED","Compact constructor declaration",type.unit,node);
                } else if (node instanceof FieldDeclaration field) {
                    field.getVariables().forEach(variable->{
                        String id=id(type.unit,variable,"FIELD");declarations.put(variable,id);
                        vertex(id,"FIELD",type.name+"."+variable.getNameAsString(),type.unit.path,line(variable),end(variable));
                        edge("DECLARES",type.id,id,"DECLARED","Field declaration",type.unit,variable);
                    });
                }
            });
        }
        for (var group:types.values()) for (TypeInfo type:group) {
            if (type.ast instanceof ClassOrInterfaceDeclaration declaration) {
                declaration.getExtendedTypes().forEach(parent->parent(type,parent,"EXTENDS"));
                declaration.getImplementedTypes().forEach(parent->parent(type,parent,"IMPLEMENTS"));
            } else if (type.ast instanceof RecordDeclaration declaration) declaration.getImplementedTypes().forEach(parent->parent(type,parent,"IMPLEMENTS"));
            else if (type.ast instanceof EnumDeclaration declaration) declaration.getImplementedTypes().forEach(parent->parent(type,parent,"IMPLEMENTS"));
        }
        for (MethodInfo method:methods) for (MethodInfo parent:methods) {
            if (method==parent || !isSubtype(method.owner,parent.owner,new HashSet<>())) continue;
            if (!method.ast.isStatic() && !parent.ast.isStatic() && !method.ast.isPrivate() && !parent.ast.isPrivate() &&
                method.ast.getNameAsString().equals(parent.ast.getNameAsString()) && sameParameters(method.ast,parent.ast))
                edge("OVERRIDES_OR_IMPLEMENTS",method.id,parent.id,"POSSIBLE","Matching source signature in a subtype; generic substitution and compiler binding are not resolved",method.owner.unit,method.ast);
        }
        for (Unit unit:units) {
            for (var imported:unit.ast.getImports()) {
                if (imported.isStatic() || imported.isAsterisk()) {
                    String target=reference(unit,"import:"+imported.getNameAsString()+(imported.isAsterisk()?".*":""),"EXTERNAL_REFERENCE");
                    edge("IMPORTS","file:"+unit.path,target,"UNRESOLVED","Static/wildcard import is retained without resolving members",unit,imported);
                } else typeEdge("IMPORTS","file:"+unit.path,imported.getNameAsString(),unit,imported,null);
            }
            for (var used:unit.ast.findAll(ClassOrInterfaceType.class)) {
                TypeInfo current=typeFor(nearestType(used));
                typeEdge("DEPENDS_ON",source(used,unit),used.getNameWithScope(),unit,used,current);
                if (isTest(used,unit)) typeEdge("TEST_REFERENCES",source(used,unit),used.getNameWithScope(),unit,used,current);
            }
            for (var call:unit.ast.findAll(MethodCallExpr.class)) calls(unit,call);
            for (var methodReference:unit.ast.findAll(MethodReferenceExpr.class)) {
                String target=reference(unit,"method-reference:"+location(methodReference)+":"+methodReference,"UNRESOLVED_CALL");
                edge("METHOD_REFERENCE",source(methodReference,unit),target,"UNRESOLVED","Deferred method reference; target and invocation are not resolved",unit,methodReference);
            }
        }
        return new Graph(VERSION,JavaSymbolIndexer.VERSION,index.status(),Instant.now(),index.javaFiles(),index.failedFiles(),
            (int)edges.stream().filter(edge->!edge.confidence.equals("DECLARED")).count(),List.copyOf(nodes.values()),List.copyOf(edges),
            index.files().stream().filter(file->!file.parsed()).toList(),List.of(
                "Calls are conservative name/arity/receiver-type candidates, not compiler-resolved bindings.",
                "Interface and virtual dispatch may have implementations outside this snapshot; candidates are not exhaustive.",
                "Reflection, dependency injection, chained-return inference, generics, method references and anonymous-class behavior are not fully resolved.",
                "Test references are source references, not runtime coverage or proof that a test invokes a call.",
                "Impact traversal is a bounded source-graph heuristic, not proof of behavioral impact."));
    }

    private void calls(Unit unit,MethodCallExpr call) {
        String from=source(call,unit);TypeInfo current=typeFor(nearestType(call));
        List<TypeInfo> receivers=new ArrayList<>();
        String reason="Unqualified call in enclosing type";
        if (anonymousContext(call)) reason="Anonymous-class context is not resolved";
        else if (call.getScope().isEmpty() || call.getScope().orElseThrow() instanceof ThisExpr) {
            if (current!=null) receivers.add(current);
        } else {
            Expression scope=call.getScope().orElseThrow();String typeName=null;
            if (scope instanceof NameExpr name) typeName=variableType(name.getNameAsString(),call,current);
            if (scope instanceof NameExpr name && typeName==null) typeName=name.getNameAsString();
            if (scope instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr && current!=null) typeName=fieldType(field.getNameAsString(),current);
            if (scope instanceof ObjectCreationExpr creation) typeName=creation.getType().getNameWithScope();
            if (typeName!=null) receivers.addAll(resolve(typeName,unit,current));
            reason="Receiver type inferred from local source; overload binding and runtime dispatch remain uncertain";
        }
        Set<String> targets=new LinkedHashSet<>();
        for (TypeInfo receiver:receivers) {
            for (MethodInfo method:methods) {
                if (!method.ast.getNameAsString().equals(call.getNameAsString()) || !arity(method.ast,call.getArguments().size())) continue;
                boolean inherited=method.owner==receiver || isSubtype(receiver,method.owner,new HashSet<>());
                boolean implementation=isSubtype(method.owner,receiver,new HashSet<>()) && !method.ast.isStatic() && !method.ast.isPrivate();
                if ((inherited || implementation) && targets.add(method.id)) {
                    edge("CALLS",from,method.id,"POSSIBLE",reason+(implementation?"; possible subtype implementation":"; declaration candidate"),unit,call);
                    if (isTest(call,unit)) edge("TEST_REFERENCES",from,method.id,"POSSIBLE","Test-source call candidate; not observed coverage",unit,call);
                }
            }
        }
        if (targets.isEmpty()) {
            String target=reference(unit,"call:"+location(call)+":"+call,"UNRESOLVED_CALL");
            edge("CALLS",from,target,"UNRESOLVED",reason+"; no matching local target. External/static imports or receiver inference may be missing",unit,call);
            if (isTest(call,unit)) edge("TEST_REFERENCES",from,target,"UNRESOLVED","Unresolved call in test source",unit,call);
        }
    }
    private String variableType(String name,MethodCallExpr call,TypeInfo current) {
        Node parent=call;
        while (parent!=null && !(parent instanceof TypeDeclaration<?>)) {
            if (parent instanceof LambdaExpr lambda && lambda.getParameters().stream().anyMatch(parameter->parameter.getNameAsString().equals(name)))
                return "<lambda receiver unresolved>";
            if (parent instanceof CallableDeclaration<?> callable) {
                var locals=callable.findAll(VariableDeclarator.class).stream().filter(variable->variable.getNameAsString().equals(name) &&
                    variable.getBegin().orElseThrow().isBefore(call.getBegin().orElseThrow()) &&
                    variable.findAncestor(BlockStmt.class).map(block->ancestor(block,call)).orElse(false)).toList();
                if (!locals.isEmpty()) {
                    if (locals.size()!=1) return "<shadowed local unresolved>";
                    var variable=locals.getFirst();
                    if (variable.getType().isVarType()) return variable.getInitializer().filter(value->value instanceof ObjectCreationExpr)
                        .map(value->((ObjectCreationExpr)value).getType().getNameWithScope()).orElse("<var receiver unresolved>");
                    return variable.getTypeAsString();
                }
                var parameter=callable.getParameters().stream().filter(value->value.getNameAsString().equals(name)).findFirst();
                if (parameter.isPresent()) return parameter.orElseThrow().getTypeAsString();
                break;
            }
            parent=parent.getParentNode().orElse(null);
        }
        return current==null?null:fieldType(name,current);
    }
    private String fieldType(String name,TypeInfo type) {
        for (var field:type.ast.getFields()) for (var variable:field.getVariables())
            if (variable.getNameAsString().equals(name)) return variable.getTypeAsString();
        if (type.ast instanceof RecordDeclaration record)
            for (var parameter:record.getParameters()) if (parameter.getNameAsString().equals(name)) return parameter.getTypeAsString();
        return null;
    }
    private void parent(TypeInfo type,ClassOrInterfaceType parent,String kind) {
        var matches=resolve(parent.getNameWithScope(),type.unit,type);
        if (matches.size()==1) type.parents.add(matches.getFirst().name);
        typeEdge(kind,type.id,parent.getNameWithScope(),type.unit,parent,type);
    }
    private void typeEdge(String kind,String from,String name,Unit unit,Node evidence,TypeInfo context) {
        var matches=resolve(name,unit,context);
        if (matches.isEmpty()) edge(kind,from,reference(unit,"type:"+name,"EXTERNAL_OR_UNRESOLVED_TYPE"),"UNRESOLVED","External, ambiguous or unresolved source type: "+name,unit,evidence);
        else for (TypeInfo match:matches) edge(kind,from,match.id,matches.size()==1?"DECLARED":"POSSIBLE",
            matches.size()==1?"Named type declaration in captured source; not a runtime dependency observation":"Ambiguous local type candidates",unit,evidence);
    }
    private List<TypeInfo> resolve(String raw,Unit unit,TypeInfo context) {
        String name=raw.replaceAll("<.*>","").replace("[]","").trim();
        LinkedHashSet<String> names=new LinkedHashSet<>();names.add(name);
        String pkg=unit.ast.getPackageDeclaration().map(value->value.getNameAsString()).orElse("");
        names.add(pkg.isEmpty()?name:pkg+"."+name);
        if (context!=null) { String owner=context.name; while (owner.contains(".")) { names.add(owner+"."+name);owner=owner.substring(0,owner.lastIndexOf('.')); } }
        for (var imported:unit.ast.getImports()) {
            if (imported.isStatic()) continue;
            String path=imported.getNameAsString();
            if (imported.isAsterisk()) names.add(path+"."+name);
            else {
                String simple=path.substring(path.lastIndexOf('.')+1);
                if (name.equals(simple) || name.startsWith(simple+".")) names.add(path+name.substring(simple.length()));
            }
        }
        LinkedHashMap<String,TypeInfo> matches=new LinkedHashMap<>();
        names.forEach(candidate->types.getOrDefault(candidate,List.of()).forEach(type->matches.putIfAbsent(type.id,type)));
        return List.copyOf(matches.values());
    }
    private boolean isSubtype(TypeInfo type,TypeInfo ancestor,Set<String> seen) {
        if (++traversalWork>2000000 || seen.size()>256) throw new IllegalArgumentException("Relationship traversal budget exceeded; use a smaller module");
        if (type==ancestor || !seen.add(type.name)) return false;
        for (String parent:type.parents) {
            if (parent.equals(ancestor.name)) return true;
            for (TypeInfo next:types.getOrDefault(parent,List.of())) if (isSubtype(next,ancestor,seen)) return true;
        }
        return false;
    }
    private boolean arity(MethodDeclaration method,int size) {
        int count=method.getParameters().size();
        return count>0 && method.getParameter(count-1).isVarArgs()?size>=count-1:count==size;
    }
    private boolean sameParameters(MethodDeclaration a,MethodDeclaration b) {
        return a.getParameters().stream().map(parameter->parameter.getTypeAsString()).toList()
            .equals(b.getParameters().stream().map(parameter->parameter.getTypeAsString()).toList());
    }
    private TypeDeclaration<?> nearestType(Node node) {
        Node parent=node.getParentNode().orElse(null);
        while (parent!=null) { if (parent instanceof TypeDeclaration<?> type) return type;parent=parent.getParentNode().orElse(null); }
        return null;
    }
    private TypeInfo typeFor(TypeDeclaration<?> ast) {
        if (ast==null) return null;
        for (var group:types.values()) for (TypeInfo type:group) if (type.ast==ast) return type;
        return null;
    }
    private boolean anonymousContext(Node node) {
        Node parent=node.getParentNode().orElse(null);
        while (parent!=null && !(parent instanceof TypeDeclaration<?>)) {
            if (parent instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) return true;
            parent=parent.getParentNode().orElse(null);
        }
        return false;
    }
    private String source(Node node,Unit unit) {
        if (anonymousContext(node)) return "file:"+unit.path;
        Node parent=node;
        while (parent!=null) { if (declarations.containsKey(parent)) return declarations.get(parent);parent=parent.getParentNode().orElse(null); }
        return "file:"+unit.path;
    }
    private boolean isTest(Node node,Unit unit) {
        if (unit.path.startsWith("src/test/")) return true;
        var method=node.findAncestor(MethodDeclaration.class);
        return method.map(value->value.getAnnotations().stream().anyMatch(annotation->Set.of("Test","ParameterizedTest","RepeatedTest","TestFactory","TestTemplate")
            .contains(annotation.getName().getIdentifier()))).orElse(false);
    }
    private boolean ancestor(Node parent,Node node) { Node current=node;while(current!=null){if(current==parent)return true;current=current.getParentNode().orElse(null);}return false; }
    private String qualified(TypeDeclaration<?> type,Unit unit) {
        List<String> names=new ArrayList<>();Node node=type;
        while(node!=null){if(node instanceof TypeDeclaration<?> declaration)names.addFirst(declaration.getNameAsString());node=node.getParentNode().orElse(null);}
        String pkg=unit.ast.getPackageDeclaration().map(value->value.getNameAsString()+".").orElse("");return pkg+String.join(".",names);
    }
    private String id(Unit unit,Node node,String kind) { return kind+":"+unit.path+":"+location(node); }
    private String location(Node node) { var range=node.getRange().orElseThrow();return range.begin.line+":"+range.begin.column; }
    private int line(Node node) { return node.getRange().orElseThrow().begin.line; }
    private int end(Node node) { return node.getRange().orElseThrow().end.line; }
    private String reference(Unit unit,String label,String kind) { String clipped=label.length()>400?label.substring(0,400)+" [truncated]":label;String id=kind+":"+UUID.nameUUIDFromBytes((unit.path+":"+label).getBytes(java.nio.charset.StandardCharsets.UTF_8));vertex(id,kind,clipped,unit.path,0,0);return id; }
    private void vertex(String id,String kind,String label,String path,int start,int end) {
        if (nodes.size()>=30000 && !nodes.containsKey(id)) throw new IllegalArgumentException("Graph exceeds 30000 nodes");
        nodes.putIfAbsent(id,new Vertex(id,kind,label,path,start,end));
    }
    private void edge(String kind,String from,String to,String confidence,String reason,Unit unit,Node evidence) {
        if (edges.size()>=50000) throw new IllegalArgumentException("Graph exceeds 50000 edges");
        var range=evidence.getRange().orElseThrow();
        edges.add(new Edge("edge:"+edges.size(),kind,from,to,confidence,reason,unit.path,range.begin.line,range.begin.column));
    }
}
