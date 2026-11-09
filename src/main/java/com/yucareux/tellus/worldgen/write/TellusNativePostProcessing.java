package com.yucareux.tellus.worldgen.write;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import com.yucareux.tellus.compat.MinecraftVersionCompat;

/** Caller-owned ProtoChunk marker transaction; preserves null slots, original lists, duplicates and dirty flag. */
public final class TellusNativePostProcessing implements TellusCompositeColumnWriter.PostProcessing {
   private final ProtoChunk chunk;
   public TellusNativePostProcessing(ProtoChunk chunk) { this.chunk = Objects.requireNonNull(chunk, "chunk"); }
   private record Snapshot(TellusNativePostProcessing owner, ShortList[] lists, short[][] contents, boolean unsaved) { }
   public Object snapshot() {
      ShortList[] lists = chunk.getPostProcessing().clone();
      short[][] contents = new short[lists.length][];
      for (int i=0;i<lists.length;i++) if(lists[i]!=null) contents[i]=lists[i].toShortArray();
      return new Snapshot(this,lists,contents,chunk.isUnsaved());
   }
   private BlockPos position(TellusCompositeColumnWriter.Cell cell) {
      if (Math.floorDiv(cell.x(),16)!=chunk.getPos().x || Math.floorDiv(cell.z(),16)!=chunk.getPos().z
         || cell.y()<chunk.getMinBuildHeight() || cell.y()>=chunk.getMaxBuildHeight()) {
         throw new IllegalArgumentException("post-processing cell outside target chunk/build bounds");
      }
      return new BlockPos(cell.x(),cell.y(),cell.z());
   }
   public void mark(TellusCompositeColumnWriter.Cell cell) {
      MinecraftVersionCompat.markPosForPostProcessing(chunk,position(cell));
   }
   public int occurrences(TellusCompositeColumnWriter.Cell cell) {
      BlockPos pos=position(cell);ShortList list=chunk.getPostProcessing()[chunk.getSectionIndex(cell.y())];
      if(list==null)return 0;short packed=ProtoChunk.packOffsetCoordinates(pos);int count=0;
      for(int i=0;i<list.size();i++)if(list.getShort(i)==packed)count++;
      return count;
   }
   public void restore(Object value) {
      if(!(value instanceof Snapshot snapshot)||snapshot.owner()!=this)throw new IllegalArgumentException("foreign post-processing snapshot");
      ShortList[] current=chunk.getPostProcessing();
      if(current.length!=snapshot.lists().length)throw new IllegalStateException("post-processing section count changed");
      for(int i=0;i<current.length;i++){
         ShortList original=snapshot.lists()[i];
         if(original!=null){original.clear();for(short packed:snapshot.contents()[i])original.add(packed);}
         current[i]=original;
      }
      chunk.setUnsaved(snapshot.unsaved());
      for(int i=0;i<current.length;i++)if(current[i]!=snapshot.lists()[i]
         ||current[i]!=null&&!Arrays.equals(current[i].toShortArray(),snapshot.contents()[i])) {
         throw new IllegalStateException("post-processing snapshot was not restored exactly");
      }
      if(chunk.isUnsaved()!=snapshot.unsaved())throw new IllegalStateException("chunk dirty flag was not restored");
   }
}
