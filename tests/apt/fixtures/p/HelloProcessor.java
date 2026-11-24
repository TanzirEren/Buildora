package p;
import java.io.*; import java.util.*;
import javax.annotation.processing.*; import javax.lang.model.SourceVersion; import javax.lang.model.element.*; import javax.tools.*;
@SupportedAnnotationTypes("p.Hello")
public class HelloProcessor extends AbstractProcessor {
  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
  @Override public boolean process(Set<? extends TypeElement> annos, RoundEnvironment env) {
    for (Element e : env.getElementsAnnotatedWith(processingEnv.getElementUtils().getTypeElement("p.Hello"))) {
      String name = e.getSimpleName() + "_Hello";
      try {
        JavaFileObject f = processingEnv.getFiler().createSourceFile("demo." + name, e);
        try (Writer w = f.openWriter()) { w.write("package demo; public class " + name + " { public static String hi() { return \"hello from " + e.getSimpleName() + "\"; } }"); }
      } catch (IOException ex) { processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.toString(), e); }
    }
    return true;
  }
}
