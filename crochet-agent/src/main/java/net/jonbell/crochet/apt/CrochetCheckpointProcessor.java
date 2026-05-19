package net.jonbell.crochet.apt;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Set;

/**
 * Compile-time validation processor for
 * {@link net.jonbell.crochet.annotation.CrochetCheckpoint}.
 *
 * <p>Checks at annotation-processing time that every
 * {@code @CrochetCheckpoint} method satisfies:
 * <ul>
 *   <li>The method is not {@code static}.
 *   <li>The method is not {@code abstract} or {@code native}.
 *   <li>Exactly one parameter carries {@code @CrochetRoot}.
 * </ul>
 *
 * <p>This processor performs <b>validation only</b> — it generates no code.
 * It runs on classes compiled from source; classes loaded from prebuilt JARs
 * are validated silently at transform time by
 * {@link net.jonbell.crochet.transform.CheckpointWrapper}.
 *
 * <p>To opt the APT into a downstream project add the {@code crochet-agent}
 * artifact as an annotation processor dependency.  The processor is registered
 * via {@code META-INF/services/javax.annotation.processing.Processor}.
 *
 * <p><b>Self-compilation note:</b> the {@code crochet-agent} module disables
 * annotation processing ({@code <proc>none</proc>}) during its own compilation
 * to avoid a bootstrapping cycle where javac tries to load the processor class
 * before it has been compiled.
 */
@SupportedAnnotationTypes("net.jonbell.crochet.annotation.CrochetCheckpoint")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class CrochetCheckpointProcessor extends AbstractProcessor {

    private static final String ROOT_ANN =
            "net.jonbell.crochet.annotation.CrochetRoot";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement checkpointAnn = processingEnv.getElementUtils()
                .getTypeElement("net.jonbell.crochet.annotation.CrochetCheckpoint");
        if (checkpointAnn == null) {
            return false;
        }
        for (Element elem : roundEnv.getElementsAnnotatedWith(checkpointAnn)) {
            if (!(elem instanceof ExecutableElement method)) {
                continue;
            }
            validate(method);
        }
        return false; // don't claim the annotation — let others see it too
    }

    private void validate(ExecutableElement method) {
        Set<Modifier> mods = method.getModifiers();

        if (mods.contains(Modifier.STATIC)) {
            error(method, "@CrochetCheckpoint cannot be applied to a static method");
        }
        if (mods.contains(Modifier.ABSTRACT)) {
            error(method, "@CrochetCheckpoint cannot be applied to an abstract method");
        }
        if (mods.contains(Modifier.NATIVE)) {
            error(method, "@CrochetCheckpoint cannot be applied to a native method");
        }

        List<? extends VariableElement> params = method.getParameters();
        int rootCount = 0;
        for (VariableElement param : params) {
            if (hasRootAnnotation(param)) {
                rootCount++;
            }
        }
        if (rootCount == 0) {
            error(method,
                    "@CrochetCheckpoint method must have exactly one parameter annotated with "
                            + "@CrochetRoot; found none");
        } else if (rootCount > 1) {
            error(method,
                    "@CrochetCheckpoint method must have exactly one @CrochetRoot parameter; "
                            + "found " + rootCount);
        }
    }

    private boolean hasRootAnnotation(VariableElement param) {
        return param.getAnnotationMirrors().stream()
                .anyMatch(m -> ROOT_ANN.equals(
                        m.getAnnotationType().asElement().toString()));
    }

    private void error(Element elem, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, elem);
    }
}
