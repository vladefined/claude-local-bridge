package dev.vladefined.taskbridge.core;

/** A place where a human can see a task and approve or reject it. */
public interface ApprovalChannel {

    /** Show the task with approve / reject controls. */
    void requestApproval(Task task);

    /** The task was decided (here, in another channel, or by timeout): remove the controls. */
    void onDecided(Task task);

    /** The approved task finished running. */
    default void onFinished(Task task) {
    }

    /**
     * A message for the running {@code task} waits for approval.
     *
     * @param sinceCursor what the agent did after the progress the caller based the message on
     * @param recentSteps the agent's last steps, so the owner sees what it is doing now
     */
    default void requestMessageApproval(Task task, TaskMessage message, java.util.List<String> sinceCursor,
                                        java.util.List<String> recentSteps) {
    }

    /** The message was decided, delivered, or could not be delivered: update or remove the controls. */
    default void onMessageUpdated(Task task, TaskMessage message) {
    }
}
