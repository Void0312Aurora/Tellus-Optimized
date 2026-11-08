package com.yucareux.tellus.worldgen.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

/** Shared Earth surface branch/range decisions over already resolved native facts. */
public final class TellusEarthSurfaceColumnRules {
   private TellusEarthSurfaceColumnRules() { }
   public enum Mode { NONE, TOP_ONLY, STANDARD, STRATA, BADLANDS }
   public record Cell<M>(int y, M material) {
      public Cell { Objects.requireNonNull(material, "material"); }
   }
   public record Span(int surfaceY, int bottomY) {
      public Span {
         if (Math.max(0L, (long)surfaceY - bottomY + 1L) > 1_048_576L) {
            throw new IllegalArgumentException("surface span exceeds cell budget");
         }
      }
      public int cellCount() { return (int)Math.max(0L, (long)surfaceY - bottomY + 1L); }
   }
   public static Mode select(boolean thinShell, boolean palettePresent, boolean underwater,
                             boolean badlands, int relief, boolean strataAvailable) {
      if (!palettePresent) return thinShell ? Mode.TOP_ONLY : Mode.NONE;
      if (!underwater && badlands && relief >= 3) return Mode.BADLANDS;
      if (!underwater && strataAvailable) return Mode.STRATA;
      return Mode.STANDARD;
   }
   public static int bottomY(int minY, int surface, int depth, boolean thinShell, int undergroundDepth) {
      if (depth < 0 || undergroundDepth < 0) throw new IllegalArgumentException("surface depth must be nonnegative");
      int ordinary = Math.max(minY + 1, surface - depth + 1);
      if (!thinShell) return ordinary;
      int support = Math.max(minY, surface - undergroundDepth);
      return Math.min(surface, Math.max(support + 1, ordinary));
   }
   public record Plan<M>(Mode mode, Span span, M top, M shallowFiller, M bottomFiller,
                          IntFunction<M> below) {
      public Plan {
         Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(span, "span");
         Objects.requireNonNull(top, "top"); Objects.requireNonNull(shallowFiller, "shallowFiller");
         Objects.requireNonNull(bottomFiller, "bottomFiller"); Objects.requireNonNull(below, "below");
         if (mode == Mode.NONE || mode == Mode.TOP_ONLY && (span.cellCount() != 1 || span.surfaceY() != span.bottomY())) {
            throw new IllegalArgumentException("surface plan mode does not match its span");
         }
      }
      public M materialAt(int y) {
         if (y < span.bottomY() || y > span.surfaceY()) throw new IllegalArgumentException("Y outside surface plan");
         if (y == span.surfaceY()) return top;
         return mode == Mode.STANDARD ? y == span.bottomY() ? bottomFiller : shallowFiller
            : Objects.requireNonNull(below.apply(y), "resolved surface material");
      }
      public void forEach(BiConsumer<Integer, M> sink) {
         Objects.requireNonNull(sink, "sink");
         for (long position = span.surfaceY(); position >= span.bottomY(); position--) {
            int y = (int)position;
            sink.accept(y, materialAt(y));
         }
      }
      public List<Cell<M>> cells() {
         List<Cell<M>> cells = new ArrayList<>(span.cellCount());
         forEach((y, material) -> cells.add(new Cell<>(y, material)));
         return List.copyOf(cells);
      }
   }
   public static <M> Plan<M> standard(Span span, M top, M shallow, M bottom) {
      return new Plan<>(Mode.STANDARD, span, top, shallow, bottom, y -> bottom);
   }
   public static <M> Plan<M> layered(Mode mode, Span span, M top, IntFunction<M> below) {
      if (mode != Mode.STRATA && mode != Mode.BADLANDS) throw new IllegalArgumentException("layered mode");
      return new Plan<>(mode, span, top, top, top, below);
   }
   public static <M> Plan<M> topOnly(int surface, M top) {
      return new Plan<>(Mode.TOP_ONLY, new Span(surface, surface), top, top, top, y -> top);
   }
}
