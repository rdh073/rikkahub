package me.rerere.workspace

import java.io.File

/**
 * Lazily, idempotently create the hidden default scratch dir (`<filesDir>/.xcloudz/scratch`) — the
 * resolved default working directory for a workspace when no override and no `working_dir` are set
 * (issue #282, W-B1/W-D2/W-B6).
 *
 * `mkdir-p` both [WorkspaceCwdPolicy.DEFAULT_SCRATCH] segments; creating twice is a no-op and
 * returns the SAME dir, with any tree already inside it preserved (W-D2). If a NON-DIRECTORY already
 * occupies `.xcloudz` or `.xcloudz/scratch`, it is NEVER overwritten or deleted — the helper falls
 * back to the [filesDir] root and returns it, never clobbering user data (W-B6).
 *
 * `java.io.File` only: the relative path mapping (`-w`) lives in the pure [WorkspaceCwdPolicy]; this
 * is the one place that touches the filesystem to materialize that default.
 */
fun ensureDefaultScratch(filesDir: File): File {
    var dir = filesDir
    for (segment in WorkspaceCwdPolicy.DEFAULT_SCRATCH) {
        val next = File(dir, segment)
        // A pre-existing non-directory at this segment is a user file we must not destroy. Fall back
        // to the files root rather than mkdir over it / delete it (W-B6) — never clobber.
        if (next.exists() && !next.isDirectory) return filesDir
        if (!next.exists() && !next.mkdir()) return filesDir
        dir = next
    }
    return dir
}
