package orbit.agent;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** The few libc calls the agent makes on Linux device files, through the foreign-function API. */
final class Libc {

    static final int O_RDONLY = 0;
    static final int O_WRONLY = 1;
    static final int O_RDWR = 2;
    static final int O_NOCTTY = 0x100;
    static final int O_NONBLOCK = 0x800;

    private Libc() {
    }

    /**
     * The C functions, looked up on the first call rather than with {@code Libc}: the ioctl numbers below are used in
     * constants of classes that load on every OS, and Windows has no {@code ioctl}. There a call fails like a missing
     * device does, since each one catches the error.
     */
    private static final class Calls {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LIBC = LINKER.defaultLookup();
        private static final MethodHandle OPEN = handle("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle READ = handle("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS,
                JAVA_LONG));
        private static final MethodHandle WRITE = handle("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS,
                JAVA_LONG));
        private static final MethodHandle IOCTL_LONG = LINKER.downcallHandle(LIBC.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG), Linker.Option.firstVariadicArg(2));
        private static final MethodHandle IOCTL_PTR = LINKER.downcallHandle(LIBC.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));
        private static final MethodHandle CLOSE = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), descriptor);
        }
    }

    /** {@code _IOR(type, nr, size)}: an ioctl that gets {@code size} bytes from the kernel. */
    static long ior(char type, int nr, int size) {
        return (2L << 30) | ((long) size << 16) | ((long) type << 8) | nr;
    }

    /** {@code _IOW(type, nr, size)}: an ioctl that passes {@code size} bytes to the kernel. */
    static long iow(char type, int nr, int size) {
        return (1L << 30) | ((long) size << 16) | ((long) type << 8) | nr;
    }

    /** {@code _IOWR(type, nr, size)}: an ioctl that passes {@code size} bytes to the kernel and gets them back. */
    static long iowr(char type, int nr, int size) {
        return (3L << 30) | ((long) size << 16) | ((long) type << 8) | nr;
    }

    /** @return the file descriptor, or a negative number when it can't be opened */
    static int open(String path, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) Calls.OPEN.invokeExact(arena.allocateFrom(path), flags);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Blocks until something can be read; 0 or less when the device is gone. */
    static long read(int fd, MemorySegment into) throws IOException {
        try {
            return (long) Calls.READ.invokeExact(fd, into, into.byteSize());
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    static void writeFully(int fd, MemorySegment from, long size) throws IOException {
        long written;
        try {
            written = (long) Calls.WRITE.invokeExact(fd, from, size);
        } catch (Throwable e) {
            throw new IOException(e);
        }
        if (written != size) {
            throw new IOException("wrote " + written + " of " + size + " bytes");
        }
    }

    /** @return true if the kernel accepted it */
    static boolean ioctl(int fd, long request, long arg) {
        try {
            return (int) Calls.IOCTL_LONG.invokeExact(fd, request, arg) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** @return true if the kernel accepted it */
    static boolean ioctl(int fd, long request, MemorySegment arg) {
        try {
            return (int) Calls.IOCTL_PTR.invokeExact(fd, request, arg) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    static void close(int fd) {
        try {
            int ignored = (int) Calls.CLOSE.invokeExact(fd);
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }
}
