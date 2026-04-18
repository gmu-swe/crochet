/**
 * Class with mutable static state whose {@code <clinit>} fires via
 * {@code Class.forName(..., init=true, ...)} rather than the usual
 * field-access path — exercising the legacy CROCHET
 * {@code ClassCoverageProbe} / {@code RootCollector} gap that the
 * {@code registerInitializedClass}-via-{@code <clinit>} emit closes.
 *
 * <p>Important: the static initializer touches no instance fields and no
 * static helper, so the runtime would not have seen this class via
 * {@code ClassMeta.of} without our coverage registration. The only reason
 * it lands in {@code checkpointAll}'s root set is the agent-emitted
 * {@code registerInitializedClass(ConfigHolder.class)} call at the top of
 * the synthesised {@code <clinit>}.
 */
public class ConfigHolder {
    public static String CONFIG = "initial";
}
