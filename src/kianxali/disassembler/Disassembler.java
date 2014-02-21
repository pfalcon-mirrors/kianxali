package kianxali.disassembler;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import kianxali.decoder.Context;
import kianxali.decoder.Data;
import kianxali.decoder.Data.DataType;
import kianxali.decoder.DecodedEntity;
import kianxali.decoder.Decoder;
import kianxali.decoder.Instruction;
import kianxali.image.ByteSequence;
import kianxali.image.ImageFile;

public class Disassembler implements AddressNameResolver, AddressNameListener {
    private static final Logger LOG = Logger.getLogger("kianxali.disassembler");

    // TODO: start at first address of the code segment, walking linear to the end
    //       while building the queue. Then iterate again until queue is empty

    private final Queue<WorkItem> workQueue;
    private final Set<DisassemblyListener> listeners;
    private final Map<Long, Function> functionInfo; // stores which trace start belongs to which function
    private final DisassemblyData disassemblyData;
    private final ImageFile imageFile;
    private final Context ctx;
    private final Decoder decoder;
    private Thread analyzeThread;

    private class WorkItem implements Comparable<WorkItem> {
        // determines whether the work should analyze code (data == null) or data (data has type set)
        public Data data;
        public Long address;

        public WorkItem(Long address, Data data) {
            this.address = address;
            this.data = data;
        }

        @Override
        public int compareTo(WorkItem o) {
            return address.compareTo(o.address);
        }
    }

    public Disassembler(ImageFile imageFile, DisassemblyData data) {
        this.imageFile = imageFile;
        this.disassemblyData = data;
        this.functionInfo = new TreeMap<Long, Function>();
        this.listeners = new CopyOnWriteArraySet<>();
        this.workQueue = new PriorityQueue<>();
        this.ctx = imageFile.createContext();
        this.decoder = ctx.createInstructionDecoder();

        disassemblyData.insertImageFileWithSections(imageFile);
        Map<Long, String> imports = imageFile.getImports();

        // add imports as functions
        for(Long memAddr : imports.keySet()) {
            Function imp = new Function(memAddr, this);
            functionInfo.put(memAddr, imp);
            disassemblyData.insertFunction(imp);
            imp.setName(imports.get(memAddr));
            onFunctionNameChange(imp);
        }

        long entry = imageFile.getCodeEntryPointMem();
        addCodeWork(entry);
    }

    public void addListener(DisassemblyListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DisassemblyListener listener) {
        listeners.remove(listener);
    }

    public synchronized void startAnalyzer() {
        if(analyzeThread != null) {
            throw new IllegalStateException("disassembler already running");
        }

        for(DisassemblyListener listener : listeners) {
            listener.onAnalyzeStart();
        }

        analyzeThread = new Thread(new Runnable() {
            public void run() {
                analyze();
            }
        });
        LOG.fine("Starting analyzer");
        analyzeThread.start();
    }

    public synchronized void stopAnalyzer() {
        if(analyzeThread != null) {
            analyzeThread.interrupt();
            analyzeThread = null;
            for(DisassemblyListener listener : listeners) {
                listener.onAnalyzeStop();
            }
            LOG.fine("Stopped analyzer");
        }
    }

    public synchronized void reanalyze(long addr) {
        disassemblyData.clearDecodedEntity(addr);

        addCodeWork(addr);
        if(analyzeThread == null) {
            startAnalyzer();
        }
    }

    private void analyze() {
        // Analyze code and data
        while(!Thread.interrupted()) {
            WorkItem item = workQueue.poll();
            if(item == null) {
                // no more work
                break;
            }
            if(item.data == null) {
                disassembleTrace(item.address);
            } else {
                analyzeData(item.data);
            }
        }

        // Propagate function information
        for(Function fun : functionInfo.values()) {
            disassemblyData.insertFunction(fun);

            // identify trampoline functions
            long start = fun.getStartAddress();
            DataEntry entry = disassemblyData.getInfoOnExactAddress(start);
            if(entry != null && entry.getEntity() instanceof Instruction) {
                Instruction inst = (Instruction) entry.getEntity();
                if(inst.isJump() && inst.getAssociatedData().size() == 1) {
                    // the function immediately jumps somewhere else, take name from there
                    Data data = inst.getAssociatedData().get(0);
                    long branch = data.getMemAddress();
                    Function realFun = functionInfo.get(branch);
                    if(realFun != null) {
                        fun.setName("!" + realFun.getName());
                        disassemblyData.tellListeners(branch);
                    }
                }
            }
        }

        stopAnalyzer();
    }

    private void addCodeWork(long address) {
        workQueue.add(new WorkItem(address, null));
    }

    private void addDataWork(Data data) {
        workQueue.add(new WorkItem(data.getMemAddress(), data));
    }

    private void disassembleTrace(long memAddr) {
        Function function = functionInfo.get(memAddr);
        while(true) {
            DecodedEntity old = disassemblyData.getEntityOnExactAddress(memAddr);
            if(old instanceof Instruction) {
                // Already visited this trace
                // If it is data, now we'll overwrite it to code
                break;
            }

            DecodedEntity covering = disassemblyData.findEntityOnAddress(memAddr);
            if(covering != null) {
                LOG.warning(String.format("%08X already covered", memAddr));
                // TODO: covers other instruction or data
                break;
            }

            if(!imageFile.isValidAddress(memAddr)) {
                // TODO: Signal this somehow?
                break;
            }

            ctx.setInstructionPointer(memAddr);
            Instruction inst = null;
            ByteSequence seq = null;
            try {
                seq = imageFile.getByteSequence(memAddr, true);
                inst = decoder.decodeOpcode(ctx, seq);
            } catch(Exception e) {
                LOG.log(Level.WARNING, String.format("Disassemble error (%s) at %08X: %s", e, memAddr, inst), e);
                // TODO: signal error
                break;
            } finally {
                if(seq != null) {
                    seq.unlock();
                }
            }

            if(inst == null) {
                // couldn't decode instruction
                // TODO: change to data
                for(DisassemblyListener listener : listeners) {
                    listener.onAnalyzeError(memAddr);
                }
                break;
            }

            disassemblyData.insertEntity(inst);

            examineInstruction(inst, function);

            if(inst.stopsTrace()) {
                break;
            }
            memAddr += inst.getSize();

            // Check if we are in a different function now. This can happen
            // if a function doesn't end with ret but just runs into different function,
            // e.g. after a call to ExitProcess
            Function newFunction = functionInfo.get(memAddr);
            if(newFunction != null) {
                function = newFunction;
            }
        }
        if(function != null && function.getEndAddress() < memAddr) {
            disassemblyData.updateFunctionEnd(function, memAddr);
        }
    }

    private void analyzeData(Data data) {
        long memAddr = data.getMemAddress();
        DataEntry cover = disassemblyData.getInfoCoveringAddress(memAddr);
        if(cover != null) {
            if(cover.hasInstruction()) {
                // data should not overwrite instruction
                return;
            } else if(cover.hasData()) {
                // TODO: new information about data, e.g. DWORD also accessed byte-wise
                return;
            }
        }

        ByteSequence seq = imageFile.getByteSequence(memAddr, true);
        try {
            data.analyze(seq);
            DataEntry entry = disassemblyData.insertEntity(data);
            for(DataEntry ref : entry.getReferences()) {
                ref.attachData(data);
                disassemblyData.tellListeners(ref.getAddress());
            }
        } catch(Exception e) {
            LOG.log(Level.WARNING, String.format("Data decode error (%s) at %08X", e, data.getMemAddress()), e);
            // TODO: change to raw data
            for(DisassemblyListener listener : listeners) {
                listener.onAnalyzeError(data.getMemAddress());
            }
            throw e;
        } finally {
            seq.unlock();
        }
    }

    // checks whether the instruction's operands could start a new trace or data
    private void examineInstruction(Instruction inst, Function function) {
        DataEntry srcEntry = disassemblyData.getInfoCoveringAddress(inst.getMemAddress());

        // check if we have branch addresses to be analyzed later
        for(long addr : inst.getBranchAddresses()) {
            if(imageFile.isValidAddress(addr)) {
                if(inst.isFunctionCall()) {
                    disassemblyData.insertReference(srcEntry, addr);
                    if(!functionInfo.containsKey(addr)) {
                        Function fun = new Function(addr, this);
                        functionInfo.put(addr, new Function(addr, this));
                        disassemblyData.insertFunction(fun);
                        onFunctionNameChange(fun);
                    }
                } else if(function != null) {
                    // if the branch is not a function call, it should belong to the current function
                    functionInfo.put(addr, function);
                }
                addCodeWork(addr);
                return;
            } else {
                // TODO: Issue warning event about invalid code address
                LOG.warning(String.format("Code at %08X references invalid address %08X", inst.getMemAddress(), addr));
                for(DisassemblyListener listener : listeners) {
                    listener.onAnalyzeError(inst.getMemAddress());
                }
            }
        }

        // check if we have associated data to be analyzed later
        for(Data data : inst.getAssociatedData()) {
            long addr = data.getMemAddress();
            if(!imageFile.isValidAddress(addr)) {
                continue;
            }
            disassemblyData.insertReference(srcEntry, addr);
            addDataWork(data);
        }

        // Check for probable pointers
        for(long addr : inst.getProbableDataPointers()) {
            if(imageFile.isValidAddress(addr)) {
                if(disassemblyData.getEntityOnExactAddress(addr) != null) {
                    continue;
                }
                disassemblyData.insertReference(srcEntry, addr);

                if(imageFile.isCodeAddress(addr)) {
                    addCodeWork(addr);
                } else {
                    Data data = new Data(addr, DataType.UNKNOWN);
                    addDataWork(data);
                }
            }
        }
    }

    @Override
    public String resolveAddress(long memAddr) {
        Function fun = functionInfo.get(memAddr);
        if(fun != null) {
            if(fun.getStartAddress() == memAddr) {
                return fun.getName();
            }
        }
        return null;
    }

    @Override
    public void onFunctionNameChange(Function fun) {
        DataEntry entry = disassemblyData.getInfoOnExactAddress(fun.getStartAddress());
        if(entry == null) {
            LOG.warning("Unkown function renamed: " + fun.getName());
            return;
        }

        disassemblyData.tellListeners(fun.getStartAddress());
        disassemblyData.tellListeners(fun.getEndAddress());
        for(DataEntry ref : entry.getReferences()) {
            disassemblyData.tellListeners(ref.getAddress());
        }
    }
}
