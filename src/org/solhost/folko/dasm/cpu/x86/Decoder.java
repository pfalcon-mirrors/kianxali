package org.solhost.folko.dasm.cpu.x86;

import java.util.List;

import org.solhost.folko.dasm.ByteSequence;
import org.solhost.folko.dasm.ImageFile;
import org.solhost.folko.dasm.OutputOptions;
import org.solhost.folko.dasm.decoder.DecodeListener;
import org.solhost.folko.dasm.decoder.DecodeTree;
import org.solhost.folko.dasm.decoder.DecodedEntity;
import org.solhost.folko.dasm.xml.OpcodeSyntax;

public class Decoder {
    private final DecodeTree<OpcodeSyntax> decodeTree;

    public Decoder(DecodeTree<OpcodeSyntax> decodeTree) {
        this.decodeTree = decodeTree;
    }

    public void decode(ImageFile image, DecodeListener listener) {
        final ByteSequence seq = image.getByteSequence(image.getCodeEntryPointMem());
        Context ctx = image.createContext();

        boolean goOn = true;
        while(goOn) {
            ctx.setFileOffset(seq.getPosition());
            Instruction inst = decodeNext(seq, ctx, decodeTree);
            if(inst != null) {
                if(inst.isPrefix()) {
                    ctx.applyPrefix(inst);
                } else {
                    inst.decode(seq, ctx);
                    long size = seq.getPosition() - ctx.getFileOffset();
                    listener.onDecode(ctx.getVirtualAddress(), (int) size, inst);
                    ctx.reset();
                }
            } else {
                listener.onDecode(ctx.getFileOffset(), 1, new DecodedEntity() {
                    public String asString(OutputOptions options) {
                        return String.format("Unknown opcode: %02X", seq.readUByte());
                    }
                });
                goOn = false;
            }
        }
    }

    private Instruction decodeNext(ByteSequence sequence, Context ctx, DecodeTree<OpcodeSyntax> tree) {
        short s = sequence.readUByte();
        ctx.addDecodedPrefix(s);

        DecodeTree<OpcodeSyntax> subTree = tree.getSubTree(s);
        if(subTree != null) {
            Instruction res = decodeNext(sequence, ctx, subTree);
            if(res != null) {
                return res;
            }
        }

        // no success in sub tree -> could be in leaf
        List<OpcodeSyntax> leaves = tree.getLeaves(s);
        if(leaves == null) {
            sequence.skip(-1);
            ctx.removeDecodedPrefixTop();
            return null;
        }

        OpcodeSyntax res = null;
        Short extension = null;
        for(OpcodeSyntax syntax : leaves) {
            if(syntax.isExtended()) {
                if(extension == null) {
                    extension = (short) ((sequence.readUByte() >> 3) & 0x07);
                    sequence.skip(-1);
                }
                if(syntax.getExtension() == extension) {
                    res = syntax;
                    break;
                }
            } else {
                // TODO: what if there are multiple syntaxes for this sequence without extension?
                // take first match for now
                res = syntax;
                break;
            }
        }
        if(res != null) {
            return new Instruction(res);
        } else {
            return null;
        }
    }
}
