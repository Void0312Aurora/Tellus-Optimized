package com.yucareux.tellus.worldgen.global;

import java.util.Arrays;
import java.util.Objects;

/**
 * Decides when the far-view development must be republished to Distant
 * Horizons, independent of Minecraft and DH.
 *
 * <p>A development depends only on the player's physical face and the chart
 * that face is drawn through. Walking inside one face changes neither, so
 * nothing is sent. Crossing a direct net edge changes the face but not the
 * chart. A seam rebase keeps the face but replaces the chart; the rigid change
 * between the old and new chart is sent with the new sheets so DH can move the
 * far view in the same frame as the camera.</p>
 */
public final class CubeNetUnfoldTracker {
   private final CubeNetUnfolding unfolding;
   private CubeNetUnfolding.Frame published;

   public CubeNetUnfoldTracker(CubeNetUnfolding unfolding) {
      this.unfolding = Objects.requireNonNull(unfolding, "unfolding");
   }

   public CubeNetUnfolding unfolding() { return this.unfolding; }

   /**
    * Returns the update to publish for a player at planar {@code (x, z)}, or
    * null when the published development is still correct. Positions without
    * a unique owner (vertex ties) keep the current development.
    */
   public Update observe(double x, double z) {
      CubeNetUnfolding.Frame frame = this.unfolding.frameAt(x, z);
      if (frame == null || frame.equals(this.published)) return null;
      int[] change = null;
      // One physical face reached through a different chart is a camera jump
      // (seam rebase or teleport). Walking onto another face, including into
      // an apron, keeps every shared placement where it was.
      if (this.published != null && frame.physical() == this.published.physical()
         && !frame.chart().equals(this.published.chart())) {
         // old screen = oldChart(data); new screen = newChart(data)
         CubeNetUnfolding.Affine c = this.published.chart().inverse().then(frame.chart());
         change = new int[]{c.m00(), c.m01(), c.m10(), c.m11(), Math.toIntExact(c.tx()), Math.toIntExact(c.tz())};
      }
      this.published = frame;
      return new Update(frame, this.unfolding.encode(this.unfolding.around(frame)), change);
   }

   /** Forget the published development, e.g. after DH reloaded its level. */
   public void reset() { this.published = null; }

   public CubeNetUnfolding.Frame published() { return this.published; }

   public record Update(CubeNetUnfolding.Frame frame, int[] sheets, int[] chartChange) {
      public Update {
         Objects.requireNonNull(frame, "frame");
         Objects.requireNonNull(sheets, "sheets");
      }

      @Override public boolean equals(Object other) {
         return other instanceof Update update && this.frame.equals(update.frame)
            && Arrays.equals(this.sheets, update.sheets) && Arrays.equals(this.chartChange, update.chartChange);
      }

      @Override public int hashCode() {
         return 31 * (31 * this.frame.hashCode() + Arrays.hashCode(this.sheets)) + Arrays.hashCode(this.chartChange);
      }
   }
}
