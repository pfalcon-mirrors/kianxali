package kianxali.scripting;

import java.util.logging.Level;
import java.util.logging.Logger;

import kianxali.decoder.DecodedEntity;
import kianxali.decoder.Instruction;
import kianxali.disassembler.Disassembler;
import kianxali.disassembler.DisassemblyData;
import kianxali.disassembler.InstructionVisitor;
import kianxali.gui.Controller;
import kianxali.loader.ByteSequence;
import kianxali.loader.ImageFile;

import org.jruby.Ruby;
import org.jruby.RubyProc;
import org.jruby.embed.ScriptingContainer;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * This class is the interface between scripts entered by the user and the controller
 * associated with the GUI
 * @author fwi
 *
 */
public class ScriptManager implements ScriptAPI {
    private static final Logger LOG = Logger.getLogger("kianxali.scripting");
    private final Controller controller;
    private final ScriptingContainer ruby;
    private final ThreadContext rubyContext;

    /**
     * Create a new script manager for a given controller.
     * The method blocks for a few seconds because loading JRuby takes quite a while
     * @param controller the controller that contains the disassembly data etc.
     */
    public ScriptManager(Controller controller) {
        this.controller = controller;
        this.ruby = new ScriptingContainer();
        this.rubyContext = ruby.getProvider().getRuntime().getCurrentContext();
        Ruby.setThreadLocalRuntime(ruby.getProvider().getRuntime());
        ruby.setWriter(controller.getLogWindowWriter());
        ruby.put("$api", this);

        LOG.config("Using Ruby version: " + ruby.getCompatVersion());
    }

    /**
     * Runs a ruby script
     * @param script the script to run
     */
    public void runScript(String script) {
        try {
            ruby.runScriptlet(script);
        } catch(Exception e) {
            String msg = "Couldn't run script: " + e.getMessage();
            LOG.log(Level.WARNING, msg, e);
            controller.showError(msg);
        }
    }

    private IRubyObject toRubyObject(Object object) {
        return JavaEmbedUtils.javaToRuby(rubyContext.getRuntime(), object);
    }

    @Override
    public void traverseCode(final RubyProc block) {
        DisassemblyData data = controller.getDisassemblyData();
        if(data == null) {
            return;
        }
        data.visitInstructions(new InstructionVisitor() {
            @Override
            public void onVisit(Instruction inst) {
                IRubyObject[] args = {toRubyObject(inst)};
                block.call(rubyContext, args);
            }
        });
    }

    @Override
    public DecodedEntity getEntityAt(Long addr) {
        DisassemblyData data = controller.getDisassemblyData();
        if(data == null || addr == null) {
            return null;
        }
        return data.getEntityOnExactAddress(addr);
    }

    @Override
    public boolean isCodeAddress(Long addr) {
        ImageFile image = controller.getImageFile();
        if(image == null || addr == null) {
            return false;
        }
        return image.isCodeAddress(addr);
    }

    @Override
    public void patchBits(Long addr, Long data, Short size) {
        if(addr == null || data == null || size == null) {
            throw new IllegalArgumentException("null-address or data or size");
        }

        ImageFile image = controller.getImageFile();
        if(image == null) {
            throw new IllegalStateException("no image loaded");
        }

        ByteSequence seq = image.getByteSequence(addr, true);
        switch(size) {
        case 8:  seq.patchByte(((byte) (data & 0xFF))); break;
        case 16: seq.patchWord(((short) (data & 0xFFFF))); break;
        case 32: seq.patchDWord(((int) (data & 0xFFFFFFFF))); break;
        case 64: seq.patchQWord(data); break;
        default: seq.unlock(); throw new UnsupportedOperationException("Invalid size: " + size);
        }
        seq.unlock();
    }

    @Override
    public void reanalyze(Long addr) {
        if(addr == null) {
            throw new IllegalArgumentException("null-address passed");
        }

        Disassembler dasm = controller.getDisassembler();
        if(dasm == null) {
            throw new IllegalStateException("no disassembler loaded");
        }

        dasm.reanalyze(addr);
    }

    @Override
    public Long readBits(Long addr, Short size) {
        if(addr == null) {
            throw new IllegalArgumentException("null-address");
        }
        if(size == null) {
            throw new IllegalArgumentException("null-size");
        }

        ImageFile image = controller.getImageFile();
        if(image == null) {
            throw new IllegalStateException("no image loaded");
        }

        ByteSequence seq = image.getByteSequence(addr, true);
        long res;
        switch(size) {
        case 8:  res = seq.readUByte(); break;
        case 16: res = seq.readUWord(); break;
        case 32: res = seq.readUDword(); break;
        case 64: res = seq.readSQword(); break;
        default: seq.unlock(); throw new UnsupportedOperationException("Invalid size: " + size);
        }
        seq.unlock();
        return res;
    }

    @Override
    public long toMemAddress(Long fileOffset) {
        return controller.getImageFile().toMemAddress(fileOffset);
    }
}
