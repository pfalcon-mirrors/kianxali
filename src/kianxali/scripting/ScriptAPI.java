package kianxali.scripting;
import kianxali.decoder.DecodedEntity;

import org.jruby.RubyProc;

/**
 * This interfaces describes the methods that are available to
 * Ruby scripts through the $api object.
 * @author fwi
 *
 */
public interface ScriptAPI {
    /**
     * Traverses all instructions in the disassembly
     * @param block a ruby block that gets passed each instruction, e.g.
     * $api.traverseCode {|instruction| ...}
     * @see kianxali.decoder.Instruction
     */
    void traverseCode(RubyProc block);

    /**
     * Checks whether a given address is a code address
     * @param addr the address to examine, null is allowed and will always be false
     * @return true iff the given address is in a code section of the image file
     */
    boolean isCodeAddress(Long addr);

    /**
     * Retrieves the code or data entity for a given address
     * @param addr the address to examine
     * @return an instance of an instruction, a data object or null
     * @see kianxali.decoder.Instruction
     * @see kianxali.decoder.Data
     */
    DecodedEntity getEntityAt(Long addr);

    /**
     * Read a raw byte contained at a virtual memory address
     * @param addr the address to examine
     * @return the raw byte contained at the given memory address or null if invalid address
     */
    Short readByte(Long addr);

    /**
     * Applies a patch to a virtual memory address
     * @param addr the address to patch
     * @param b the byte to write at the given address
     */
    void patchByte(Long addr, Short b);

    /**
     * Causes the disassembler to reanalyze the trace at the given address
     * @param addr the memory address to reanalyze, subsequent address will also be reanalyzed
     */
    void reanalyze(Long addr);
}
