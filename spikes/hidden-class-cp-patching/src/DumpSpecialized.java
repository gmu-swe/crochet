// Diagnostic helper: dump a specialized class file to disk so we can run
// `javap -v build/UserA_CRIJFast.class` and visually confirm the CP was
// rewritten correctly. Useful when debugging the approach-B rewriter.
import java.io.FileOutputStream;

public class DumpSpecialized {
    public static void main(String[] args) throws Exception {
        byte[] template = TemplateBytes.build();
        try (FileOutputStream fos = new FileOutputStream("build/Template.class")) {
            fos.write(template);
        }
        SpecializerB.SpecSpec spec = new SpecializerB.SpecSpec(
            UserA.class, RoleIface.class,
            "UserA$$CRIJFast",
            "RuntimeAgent", "checkpointCalled", "rollbackCalled");
        byte[] rewritten = SpecializerB.rewrite(template, spec);
        try (FileOutputStream fos = new FileOutputStream("build/UserA_CRIJFast.class")) {
            fos.write(rewritten);
        }
        System.out.println("wrote build/Template.class and build/UserA_CRIJFast.class");
    }
}
