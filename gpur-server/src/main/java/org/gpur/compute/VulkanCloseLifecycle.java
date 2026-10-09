package org.gpur.compute;

/** Guards native teardown so resources cannot be destroyed before queue idle is confirmed. */
final class VulkanCloseLifecycle {
    private enum State { OPEN, CLOSING, DEVICE_IDLE, DESTROYED }

    private State state = State.OPEN;

    void requestClose() {
        if (this.state == State.OPEN) this.state = State.CLOSING;
    }

    void confirmDeviceIdle() {
        if (this.state != State.CLOSING) throw new IllegalStateException("close was not requested");
        this.state = State.DEVICE_IDLE;
    }

    boolean mayDestroyNativeResources() {
        return this.state == State.DEVICE_IDLE;
    }

    void markDestroyed() {
        if (!this.mayDestroyNativeResources()) throw new IllegalStateException("Vulkan device is not idle");
        this.state = State.DESTROYED;
    }
}
