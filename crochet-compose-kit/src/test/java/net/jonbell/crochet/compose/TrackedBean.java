package net.jonbell.crochet.compose;

/**
 * Simple user-defined bean for composition tests.
 * Being in the user class-loader means Crochet will instrument it via the
 * -javaagent path, making it a valid target for checkpoint/rollback.
 */
final class TrackedBean {

    private String value;

    TrackedBean(String value) {
        this.value = value;
    }

    String getValue() {
        return value;
    }

    void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "TrackedBean{value=" + value + "}";
    }
}
