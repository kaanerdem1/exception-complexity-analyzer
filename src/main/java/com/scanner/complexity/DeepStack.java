package com.scanner.complexity;

/**
 * PMD ayrıştırması ve metot metrikleri özyinelemelidir. Dosya işçisinin yığını
 * yetmezse taşma bütün dosyayı düşürür. Bu çağrı işi geniş yığınlı ayrı bir
 * iş parçacığında çalıştırır; o parça ölürse çağıran iş parçacığı ayakta kalır.
 */
final class DeepStack {

    /** Derin servis sınıfları varsayılan 512KB–1MB yığına sığmıyor. */
    static final long STANDARD = 16L << 20;
    static final long TALL = 32L << 20;
    /** Dosya ağacı ve metot metriği aynı merdiveni kullanır. 32MB Collateral servislerinde yetmedi. */
    static final long[] FILE_ATTEMPTS = {STANDARD, TALL, 64L << 20, 128L << 20};

    @FunctionalInterface
    interface Task<T> {
        T run() throws Exception;
    }

    private DeepStack() {
    }

    static <T> T call(long stackBytes, Task<T> task) throws Exception {
        class Box {
            T value;
            Throwable error;
            boolean done;
        }
        Box box = new Box();
        Thread thread = new Thread(null, () -> {
            try {
                box.value = task.run();
                box.done = true;
            } catch (Throwable caught) {
                box.error = caught;
            }
        }, "pmd-deep", stackBytes);
        thread.setDaemon(true);
        thread.start();
        thread.join();
        if (box.error instanceof Exception exception) {
            throw exception;
        }
        if (box.error instanceof VirtualMachineError error) {
            throw error;
        }
        if (box.error instanceof Error error) {
            throw error;
        }
        if (box.error != null) {
            throw new RuntimeException(box.error);
        }
        if (!box.done) {
            throw new StackOverflowError(
                    "Ölçüm iş parçacığı yığın taşmasıyla durdu. Dosyanın diğer metotları sürer.");
        }
        return box.value;
    }

    /** 16 MB yeterse biter. Taşarsa 32, 64 ve 128 MB denenir. Başka hata tekrar denenmez. */
    static <T> T callGrowing(Task<T> task) throws Exception {
        VirtualMachineError last = null;
        for (long stack : FILE_ATTEMPTS) {
            try {
                return call(stack, task);
            } catch (VirtualMachineError overflow) {
                last = overflow;
            }
        }
        throw last == null
                ? new StackOverflowError("Yığın denemesi sonuç vermedi.")
                : last;
    }
}
