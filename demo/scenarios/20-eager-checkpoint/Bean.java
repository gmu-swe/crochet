import net.jonbell.crochet.annotation.CrochetEager;

/**
 * Eager-checkpoint fixture. @CrochetEager selects the shallow-copy strategy:
 *  - No Fast-proxy / klass-swap
 *  - obj.getClass() remains Bean.class across checkpoints
 *  - checkpoint() immediately does a shallow field copy into a shadow
 */
@CrochetEager
public class Bean {
    public int x;

    public Bean(int x) {
        this.x = x;
    }
}
