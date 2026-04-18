import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Bean bean = new Bean(1);
        Class<?> klassBefore = bean.getClass();
        System.out.println("before checkpoint : x=" + bean.x
                + " class=" + klassBefore.getSimpleName());

        int v = CheckpointRollbackAgent.checkpoint(bean);
        System.out.println("checkpoint version: " + v
                + " class=" + bean.getClass().getSimpleName());

        if (bean.getClass() != klassBefore) {
            System.out.println("SCENARIO FAIL: klass swapped for @CrochetEager class: "
                    + bean.getClass());
            System.exit(1);
        }

        bean.x = 42;
        System.out.println("after mutation    : x=" + bean.x);

        CheckpointRollbackAgent.rollback(bean, v);
        System.out.println("after rollback    : x=" + bean.x
                + " class=" + bean.getClass().getSimpleName());

        if (bean.x == 1 && bean.getClass() == klassBefore) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: x=" + bean.x
                    + " class=" + bean.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
