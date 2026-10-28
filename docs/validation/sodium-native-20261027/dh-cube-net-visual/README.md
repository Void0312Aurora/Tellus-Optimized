# Cube-Net + DH long-distance visual check (2026-10-27)

This is a bounded graphical acceptance run using Prism/Xwayland with Tellus 0.8.4, Sodium `0.5.13+mc1.20.1`, Iris 1.7.6, ComplementaryReimagined r5.5.1, and the Tellus DH fork `3.3.1-tellus.1`.

## Result

- **Cube-Net seam transfer: passed in the live client.** The server staged 121 destination chunks and the client reported `Cube-net client render staging ready ... sections=1296`. The isolated server then recorded the deferred seam transfer from `(15360.5, 32.5)` to `(10207.5, 100.0, 5120.5)`, and the client accepted post-commit motion and submitted committed frames at the canonical position.
- **Visual framebuffer: passed.** `cube-net-cut-ready.png` shows the source-side water/pool and the blue/red markers; `cube-net-rebased.png` shows the canonical-side guide and markers after the transfer.
- **DH long-distance stability: partial.** DH initialized OpenGL successfully and remained connected throughout the transfer. The reported pending LOD count fell from `67,072` to `20,992` after the rebase, then remained at `20,992` during the sample window. This proves active DH transport/render integration, but not full long-distance LOD completion.
- **No client crash or renderer exception** was observed. The client log contains Iris/DH known warnings (shader uniform lookup and the OpenGL 3.3 capability probe); `GLProxy creation successful` and `Renderer setup complete` followed them.

## Configuration gate

The first attempt used view/simulation distance 8 against a world apron of 7 chunks and was rejected by the runtime, which correctly required 11 chunks. The isolated server was restarted with `view-distance=4` and `simulation-distance=4`; the same world then passed the apron gate because 7 chunks is sufficient.

## Artifacts

- `cube-net-cut-ready.png`: source-side rendered fixture after native staging.
- `cube-net-rebased.png`: canonical-side rendered fixture after seam transfer.
- `server-key-events-20261027.log`: filtered server staging/rebase evidence.
- `client-key-events-20261027.log`: filtered client staging/frame/DH evidence.
- `client-latest-20261027.log`, `server-latest-20261027.log`: complete logs for this run.
- `SHA256SUMS`: hashes for the captured artifacts.
