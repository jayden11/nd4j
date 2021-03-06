package org.nd4j.jita.constant;

import org.bytedeco.javacpp.Pointer;
import org.nd4j.jita.allocator.enums.AllocationStatus;
import org.nd4j.jita.allocator.impl.AllocationPoint;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.allocator.pointers.CudaPointer;
import org.nd4j.jita.allocator.utils.AllocationUtils;
import org.nd4j.jita.conf.Configuration;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.jita.flow.FlowController;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.cache.ArrayDescriptor;
import org.nd4j.linalg.cache.ConstantHandler;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.buffer.CudaDoubleDataBuffer;
import org.nd4j.linalg.jcublas.buffer.CudaFloatDataBuffer;
import org.nd4j.linalg.jcublas.buffer.CudaHalfDataBuffer;
import org.nd4j.linalg.jcublas.buffer.CudaIntDataBuffer;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by raver on 08.06.2016.
 */
public class ProtectedCudaConstantHandler implements ConstantHandler {
    private static ProtectedCudaConstantHandler ourInstance = new ProtectedCudaConstantHandler();

    protected Map<Integer, AtomicLong> constantOffsets = new HashMap<>();
    protected Map<Integer, Semaphore> deviceLocks = new ConcurrentHashMap<>();

    protected Map<Integer, Map<ArrayDescriptor, DataBuffer>> buffersCache = new HashMap<>();
    protected Map<Integer, Pointer> deviceAddresses = new HashMap<>();
    protected FlowController flowController;

    protected static final ConstantProtector protector = ConstantProtector.getInstance();

    private static Logger logger = LoggerFactory.getLogger(ProtectedCudaConstantHandler.class);

    private static final int MAX_CONSTANT_LENGTH = 49152;
    private static final int MAX_BUFFER_LENGTH = 272;

    protected Semaphore lock = new Semaphore(1);
    private boolean resetHappened = false;


    public static ProtectedCudaConstantHandler getInstance() {
        return ourInstance;
    }

    private ProtectedCudaConstantHandler() {}

    /**
     * This method removes all cached constants
     */
    @Override
    public void purgeConstants() {
        buffersCache = new HashMap<>();

        protector.purgeProtector();

        resetHappened = true;
        logger.info("Resetting Constants...");

        for (Integer device : constantOffsets.keySet()) {
            constantOffsets.get(device).set(0);
            buffersCache.put(device, new ConcurrentHashMap<ArrayDescriptor, DataBuffer>());
        }
    }

    /**
     * Method suited for debug purposes only
     *
     * @return
     */
    protected int amountOfEntries(int deviceId) {
        ensureMaps(deviceId);
        return buffersCache.get(0).size();
    }

    /**
     * This method moves specified dataBuffer to CUDA constant memory space.
     *
     * PLEASE NOTE: CUDA constant memory is limited to 48KB per device.
     *
     * @param dataBuffer
     * @return
     */
    @Override
    public synchronized long moveToConstantSpace(DataBuffer dataBuffer) {
        // now, we move things to constant memory
        Integer deviceId = AtomicAllocator.getInstance().getDeviceId();
        ensureMaps(deviceId);

        AllocationPoint point = AtomicAllocator.getInstance().getAllocationPoint(dataBuffer);

        long requiredMemoryBytes = AllocationUtils.getRequiredMemory(point.getShape());
        //logger.info("shape: " + point.getShape());
        // and release device memory :)

        long currentOffset = constantOffsets.get(deviceId).get();
        CudaContext context = (CudaContext) AtomicAllocator.getInstance().getDeviceContext().getContext();
        if (currentOffset + requiredMemoryBytes >= MAX_CONSTANT_LENGTH || requiredMemoryBytes > MAX_BUFFER_LENGTH) {
            if (point.getAllocationStatus() == AllocationStatus.HOST
                            && CudaEnvironment.getInstance().getConfiguration().getMemoryModel() == Configuration.MemoryModel.DELAYED) {
                AtomicAllocator.getInstance().getMemoryHandler().alloc(AllocationStatus.DEVICE, point, point.getShape(),
                                false);
            }

            if (NativeOpsHolder.getInstance().getDeviceNativeOps().memcpyAsync(point.getPointers().getDevicePointer(), point.getPointers().getHostPointer(),
                            requiredMemoryBytes, 1, context.getSpecialStream()) == 0) {
                throw new ND4JIllegalStateException("memcpyAsync failed");
            }
            flowController.commitTransfer(context.getSpecialStream());

            point.setConstant(true);
            point.tickDeviceWrite();
            point.tickHostRead();
            point.setDeviceId(deviceId);

            protector.persistDataBuffer(dataBuffer);

            return 0;
        }

        long bytes = requiredMemoryBytes;
        // hack for misalignment avoidance for 16bit data type
        if (dataBuffer.dataType() == DataBuffer.Type.HALF) {
            if (bytes % 4 != 0) {
                bytes += 2;
            }
        } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
            // for double data type, we must be assured, that all DOUBLE pointers are starting from even addresses, to avoid banks spills
            long div = bytes / 4;
            if (div % 2 != 0)
                bytes += 4;

            // for possible changes of dtype in the same jvm, we skip few bytes in constant memory
            div = currentOffset / 4;
            while (div % 2 != 0) {
                currentOffset = constantOffsets.get(deviceId).addAndGet(4);
                div = currentOffset / 4;

                // just break out, if we're stepped beyond constant memory space
                if (currentOffset > MAX_CONSTANT_LENGTH)
                    break;
            }
        }

        currentOffset = constantOffsets.get(deviceId).getAndAdd(bytes);

        if (currentOffset >= MAX_CONSTANT_LENGTH) {
            if (point.getAllocationStatus() == AllocationStatus.HOST
                            && CudaEnvironment.getInstance().getConfiguration().getMemoryModel() == Configuration.MemoryModel.DELAYED) {
                AtomicAllocator.getInstance().getMemoryHandler().alloc(AllocationStatus.DEVICE, point, point.getShape(),
                                false);
            }

            if (NativeOpsHolder.getInstance().getDeviceNativeOps().memcpyAsync(point.getPointers().getDevicePointer(), point.getPointers().getHostPointer(),
                            requiredMemoryBytes, 1, context.getSpecialStream()) == 0) {
                throw new ND4JIllegalStateException("memcpyAsync failed");
            }
            flowController.commitTransfer(context.getSpecialStream());

            point.setConstant(true);
            point.tickDeviceWrite();
            point.tickHostRead();
            point.setDeviceId(deviceId);

            protector.persistDataBuffer(dataBuffer);

            return 0;
        }



        NativeOpsHolder.getInstance().getDeviceNativeOps().memcpyConstantAsync(currentOffset, point.getPointers().getHostPointer(), requiredMemoryBytes, 1,
                        context.getSpecialStream());
        flowController.commitTransfer(context.getSpecialStream());

        long cAddr = deviceAddresses.get(deviceId).address() + currentOffset;

        //if (resetHappened)
        //    logger.info("copying to constant: {}, bufferLength: {}, bufferDtype: {}, currentOffset: {}, currentAddres: {}", requiredMemoryBytes, dataBuffer.length(), dataBuffer.dataType(), currentOffset, cAddr);

        point.setAllocationStatus(AllocationStatus.CONSTANT);
        point.getPointers().setDevicePointer(new CudaPointer(cAddr));
        point.setConstant(true);
        point.tickDeviceWrite();
        point.setDeviceId(deviceId);
        point.tickHostRead();


        protector.persistDataBuffer(dataBuffer);

        return cAddr;
    }

    /**
     * PLEASE NOTE: This method implementation is hardware-dependant.
     * PLEASE NOTE: This method does NOT allow concurrent use of any array
     *
     * @param dataBuffer
     * @return
     */
    @Override
    public DataBuffer relocateConstantSpace(DataBuffer dataBuffer) {
        // we always assume that data is sync, and valid on host side
        Integer deviceId = AtomicAllocator.getInstance().getDeviceId();
        ensureMaps(deviceId);

        if (dataBuffer instanceof CudaIntDataBuffer) {
            int[] data = dataBuffer.asInt();
            return getConstantBuffer(data);
        } else if (dataBuffer instanceof CudaFloatDataBuffer) {
            float[] data = dataBuffer.asFloat();
            return getConstantBuffer(data);
        } else if (dataBuffer instanceof CudaDoubleDataBuffer) {
            double[] data = dataBuffer.asDouble();
            return getConstantBuffer(data);
        } else if (dataBuffer instanceof CudaHalfDataBuffer) {
            float[] data = dataBuffer.asFloat();
            return getConstantBuffer(data);
        }

        throw new IllegalStateException("Unknown CudaDataBuffer type");
    }

    private void ensureMaps(Integer deviceId) {
        if (!buffersCache.containsKey(deviceId)) {
            if (flowController == null)
                flowController = AtomicAllocator.getInstance().getFlowController();

            try {
                synchronized (this) {
                    if (!buffersCache.containsKey(deviceId)) {

                        // TODO: this op call should be checked
                        //nativeOps.setDevice(new CudaPointer(deviceId));

                        buffersCache.put(deviceId, new ConcurrentHashMap<ArrayDescriptor, DataBuffer>());
                        constantOffsets.put(deviceId, new AtomicLong(0));
                        deviceLocks.put(deviceId, new Semaphore(1));

                        Pointer cAddr = NativeOpsHolder.getInstance().getDeviceNativeOps().getConstantSpace();
                        //                    logger.info("constant pointer: {}", cAddr.address() );

                        deviceAddresses.put(deviceId, cAddr);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * This method returns DataBuffer with contant equal to input array.
     *
     * PLEASE NOTE: This method assumes that you'll never ever change values within result DataBuffer
     *
     * @param array
     * @return
     */
    @Override
    public DataBuffer getConstantBuffer(int[] array) {
        //  logger.info("getConstantBuffer(int[]) called");
        ArrayDescriptor descriptor = new ArrayDescriptor(array);

        Integer deviceId = AtomicAllocator.getInstance().getDeviceId();

        ensureMaps(deviceId);

        if (!buffersCache.get(deviceId).containsKey(descriptor)) {
            // we create new databuffer
            //logger.info("Creating new constant buffer...");
            DataBuffer buffer = Nd4j.createBufferDetached(array);
            buffer.setConstant(true);

            // now we move data to constant memory, and keep happy
            moveToConstantSpace(buffer);

            buffersCache.get(deviceId).put(descriptor, buffer);
            return buffer;
        } //else logger.info("Reusing constant buffer...");

        return buffersCache.get(deviceId).get(descriptor);
    }

    /**
     * This method returns DataBuffer with contant equal to input array.
     *
     * PLEASE NOTE: This method assumes that you'll never ever change values within result DataBuffer
     *
     * @param array
     * @return
     */
    @Override
    public DataBuffer getConstantBuffer(float[] array) {
        //   logger.info("getConstantBuffer(float[]) called");
        ArrayDescriptor descriptor = new ArrayDescriptor(array);

        Integer deviceId = AtomicAllocator.getInstance().getDeviceId();

        ensureMaps(deviceId);

        if (!buffersCache.get(deviceId).containsKey(descriptor)) {
            // we create new databuffer
                 //logger.info("Creating new constant buffer...");
            DataBuffer buffer = Nd4j.createBufferDetached(array);
            buffer.setConstant(true);

            // now we move data to constant memory, and keep happy
            moveToConstantSpace(buffer);

            buffersCache.get(deviceId).put(descriptor, buffer);
            return buffer;
        } // else logger.info("Reusing constant buffer...");

        return buffersCache.get(deviceId).get(descriptor);
    }

    /**
     * This method returns DataBuffer with contant equal to input array.
     *
     * PLEASE NOTE: This method assumes that you'll never ever change values within result DataBuffer
     *
     * @param array
     * @return
     */
    @Override
    public DataBuffer getConstantBuffer(double[] array) {
                //logger.info("getConstantBuffer(double[]) called: {}", Arrays.toString(array));
        ArrayDescriptor descriptor = new ArrayDescriptor(array);

        Integer deviceId = AtomicAllocator.getInstance().getDeviceId();

        ensureMaps(deviceId);

        if (!buffersCache.get(deviceId).containsKey(descriptor)) {
            // we create new databuffer
            //logger.info("Creating new constant buffer...");
            DataBuffer buffer = Nd4j.createBufferDetached(array);
            buffer.setConstant(true);

            // now we move data to constant memory, and keep happy
            moveToConstantSpace(buffer);

            buffersCache.get(deviceId).put(descriptor, buffer);
            return buffer;
        } //else logger.info("Reusing constant buffer...");

        return buffersCache.get(deviceId).get(descriptor);
    }
}
