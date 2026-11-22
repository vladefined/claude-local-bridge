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
}
