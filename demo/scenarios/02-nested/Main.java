import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Child c = new Child(7);
        Parent p = new Parent("root", c);
        System.out.println("before: parent.name=" + p.name + " child.value=" + p.child.value);

        int v = CheckpointRollbackAgent.checkpoint(p);
        System.out.println("checkpoint version: " + v);

        p.name = "renamed";
        p.child.value = 99;
        System.out.println("after mutation: parent.name=" + p.name + " child.value=" + p.child.value);

        CheckpointRollbackAgent.rollback(p, v);
        System.out.println("after rollback: parent.name=" + p.name + " child.value=" + p.child.value);

        if ("root".equals(p.name) && p.child.value == 7) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: expected name=root value=7, got name=" + p.name
                    + " value=" + p.child.value);
            System.exit(1);
        }
    }
}
