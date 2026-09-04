import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassPreset;

public class DockGlassPresetTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(DockGlassPreset.MODE == 3, "do not reuse legacy custom blur value 2");
        check(DockGlassPreset.MATERIAL_TYPE == 1, "verified native glass material type");
        for (boolean dark : new boolean[]{false, true}) {
            float[] params = DockGlassPreset.parameters(dark);
            check(params.length == 42, "JNI requires exactly 42 floats");
            for (float value : params) check(Float.isFinite(value), "all parameters finite");
            check(params[18] == 1f, "alpha is index 18, not the edge field");
            check(java.util.Arrays.hashCode(params) == -1053074018,
                    "bit-exact 42-float control-center default preset, independently extracted from APK");
            check(params[19] == 72f, "control-center edge parameter");
            check(params[32] == 4f, "control-center refractive index");
            check(params[14] == .1f && params[16] == .3f, "control-center color mixing");
            check(params[33] == 2f, "control-center background saturation");
            check((DockGlassPreset.fallbackColor(dark) >>> 24) == 0x18, "glass fallback has a light tint");
            params[18] = 0f;
            check(DockGlassPreset.parameters(dark)[18] == 1f, "presets are not shared mutable arrays");
        }
        check(java.util.Arrays.equals(DockGlassPreset.parameters(false), DockGlassPreset.parameters(true)),
                "control-center default token has no day/night branch");
        check(DockGlassPreset.SMALL_BLUR_RADIUS == 110 && DockGlassPreset.BIG_BLUR_RADIUS == 110,
                "control-center default dual blur radii");
        check(DockGlassPreset.GLASS_ENHANCE_FLAG == 0x2000, "control-center glass enhancement flag");
        System.out.println("DockGlassPreset tests passed");
    }
}
