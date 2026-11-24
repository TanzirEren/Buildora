import com.tenix.buildora.build.BuildEngine;
import com.tenix.buildora.build.BuildEnv;
import com.tenix.buildora.build.KeyTool;
import com.tenix.buildora.build.Signer;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.Variant;

import java.io.File;

/**
 * Desktop harness: runs the REAL BuildEngine (aapt2, ECJ with annotation processing, D8, apksig) on a project.
 * Usage: AptBuildHarness <projectRoot> <variant> <workDir> <m2Dir> [offline] [dexProcessors]
 * Environment (paths): TC = toolchain directory containing android.jar, lambda-stubs.jar, aapt2.
 */
public class AptBuildHarness {
    public static void main(String[] a) throws Exception {
        final File root = new File(a[0]);
        final String variantName = a[1];
        final File work = new File(a[2]);
        final File m2 = new File(a[3]);
        final boolean offline = a.length > 4 && a[4].equals("offline");
        final boolean dexProc = a.length > 5 && a[5].equals("dex");
        final File tc = new File(System.getenv("TC"));
        work.mkdirs();
        final File keyFile = new File(work, "test.keystore");
        if (!keyFile.isFile()) KeyTool.generate(keyFile, "key", "pass12".toCharArray(), "pass12".toCharArray(), "Test", "", "", "", "", "US", 5, 2048);

        BuildEnv env = new BuildEnv() {
            public File aapt2() { return new File(tc, "aapt2"); }
            public File androidJar() { return new File(tc, "android.jar"); }
            public File lambdaStubs() { return new File(tc, "lambda-stubs.jar"); }
            public File m2Dir() { return m2; }
            public File dexCacheDir() { return new File(work, "dexcache"); }
            public void prepare() { }
            public String aapt2Version() { return "harness"; }
            public boolean incremental() { return true; }
            public boolean cleanIntermediates() { return false; }
            public boolean autoVersion() { return false; }
            public boolean dexRelease() { return false; }
            public boolean verifySignature() { return true; }
            public boolean crashReports() { return false; }
            public boolean autoLibraries() { return false; }
            public boolean offline() { return offline; }
            public boolean dexProcessors() { return dexProc; }
            public int nativeAlign() { return 4096; }
            public Signer.Key signingKey(String id) {
                Signer.Key k = new Signer.Key();
                k.file = keyFile; k.alias = "key"; k.storePass = "pass12"; k.keyPass = "pass12"; k.name = "test key";
                return k;
            }
        };
        Project p = Project.load(root);
        BuildEngine.Options o = new BuildEngine.Options();
        o.variant = Variant.resolve(p, variantName, "");
        final StringBuilder log = new StringBuilder();
        BuildEngine e = new BuildEngine(env, p, o, new BuildEngine.Listener() {
            public void onStage(String s, int pct) { System.out.println("## " + s + " " + pct + "%"); }
            public void onLog(String l) { System.out.println(l); }
        });
        BuildEngine.Result r = e.build();
        System.out.println("RESULT success=" + r.success + " apk=" + (r.apk == null ? null : r.apk.getName() + " " + r.apk.length()));
        if (!r.success) {
            System.out.println("ERROR TEXT:\n" + r.errorText);
            for (BuildEngine.Diag d : r.diags) System.out.println("DIAG " + (d.error ? "E " : "W ") + d);
        }
        System.exit(r.success ? 0 : 1);
    }
}
