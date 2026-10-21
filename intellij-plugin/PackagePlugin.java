import java.nio.file.*;
import java.util.zip.*;
public class PackagePlugin {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]);
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])));
      var files=Files.walk(root)) {
   for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
    out.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\','/')));
    Files.copy(file,out); out.closeEntry();
   }
  }
 }
}
