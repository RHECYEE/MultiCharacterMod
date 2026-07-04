package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.civil.StrategicMissionManager;

import java.util.List;

/** Launch a strategic mission from the Mission Dispatch GUI: type + party (characters + citizens). */
public class C2SMissionDispatch implements IMessage {

    private int type, characters, citizens, x, z;

    public C2SMissionDispatch() {}

    public C2SMissionDispatch(int type, int characters, int citizens, int x, int z) {
        this.type = type; this.characters = characters; this.citizens = citizens; this.x = x; this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        type = buf.readByte();
        characters = buf.readByte();
        citizens = buf.readByte();
        x = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(type);
        buf.writeByte(characters);
        buf.writeByte(citizens);
        buf.writeInt(x);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<C2SMissionDispatch, IMessage> {
        @Override
        public IMessage onMessage(C2SMissionDispatch msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                WorldServer world = (WorldServer) player.world;
                // Sum the assigned characters' skill for this mission from the roster's roles.
                int skillUnits = 0;
                try {
                    List<co.runed.multicharacter.character.Character> roster =
                            co.runed.multicharacter.MultiCharacterMod.getCharacterManager()
                                    .getCharacters(player.getUniqueID());
                    int take = Math.max(0, Math.min(msg.characters, roster.size()));
                    for (int i = 0; i < take; i++) {
                        co.runed.multicharacter.character.Character c = roster.get(i);
                        int best = 1;
                        if (c.getRoles() != null) {
                            for (String role : c.getRoles()) {
                                best = Math.max(best, StrategicMissionManager.charSkillBonus(role, msg.type));
                            }
                        }
                        skillUnits += best;
                    }
                } catch (Throwable ignored) {}
                int citizens = Math.max(0, Math.min(16, msg.citizens));
                StrategicMissionManager.dispatch(world, player, msg.type, skillUnits, citizens, msg.x, msg.z);
            });
            return null;
        }
    }
}
