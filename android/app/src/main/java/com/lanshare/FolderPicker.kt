package com.lanshare

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * One entry of a folder tree. The walk only needs these facts, so tests drive it
 * with plain objects while the SAF adapter fills them from `DocumentsContract`.
 */
interface TreeEntry {
    val isDirectory: Boolean

    /**
     * Children in provider order. A node that cannot be listed throws; the walk
     * skips that node and keeps going.
     */
    fun children(): List<TreeEntry>
}

/**
 * One folder selection can add at most this many files, so a huge tree cannot
 * flood the chat. Same meaning and value as the web's `MAX_FOLDER_FILES`
 * (`App.tsx:2328`).
 */
const val MAX_FOLDER_FILES = 100

/** The files found under one folder, already flat and capped at the limit. */
data class FolderWalkResult(
    val files: List<TreeEntry>,
    /** True when the tree held more files than [MAX_FOLDER_FILES] (web `truncated`). */
    val truncated: Boolean,
)

/**
 * Walks [root] depth-first and returns every file it can reach, in a stable
 * pre-order: a directory's files come first, then its subdirectories in
 * provider order. The directory structure is not kept — the transfer protocol
 * carries a flat list (web `collectDroppedFiles`).
 *
 * - A directory whose listing throws is skipped on its own, so one unreadable
 *   folder never costs the rest of the selection.
 * - The walk stops at [limit]: the overflow is reported as [FolderWalkResult.truncated]
 *   and no further directory is opened, so a huge tree is not enumerated past
 *   the cap.
 */
fun collectFolderTreeFiles(
    root: TreeEntry,
    limit: Int = MAX_FOLDER_FILES,
    childrenProvider: (TreeEntry) -> List<TreeEntry> = { it.children() },
): FolderWalkResult {
    val files = mutableListOf<TreeEntry>()
    // `truncated` is only true once a readable file had to be left out, so an
    // unreadable branch is reported as skipped, never as "too many".
    var truncated = false
    val pending = ArrayDeque<TreeEntry>()
    pending.addLast(root)
    while (pending.isNotEmpty() && !truncated) {
        val directory = pending.removeLast()
        val children = runCatching { childrenProvider(directory) }.getOrElse { emptyList() }
        // Pre-order: queue subdirectories behind the files of this directory.
        val subdirectories = mutableListOf<TreeEntry>()
        for (child in children) {
            if (files.size >= limit) {
                truncated = true
                break
            }
            if (child.isDirectory) {
                subdirectories += child
            } else {
                files += child
            }
        }
        // Push in reverse so the first subdirectory is the next one visited.
        for (index in subdirectories.lastIndex downTo 0) {
            pending.addLast(subdirectories[index])
        }
    }
    return FolderWalkResult(files = files, truncated = truncated)
}

/**
 * What the attachment panel says after a folder pick, if anything:
 * [fileCount] files went to the send path, and [truncated] marks an overflow.
 */
enum class FolderPickNotice {
    EMPTY,
    TOO_MANY,
    NONE,
}

fun folderPickNotice(fileCount: Int, truncated: Boolean): FolderPickNotice = when {
    fileCount > 0 && truncated -> FolderPickNotice.TOO_MANY
    fileCount > 0 -> FolderPickNotice.NONE
    else -> FolderPickNotice.EMPTY
}

/** One file found under a picked SAF tree, ready for the existing send path. */
data class FolderPickedFile(val name: String, val uri: Uri)

/**
 * The Android side of the walk: one SAF document. [children] reads its listing
 * through `DocumentsContract`, so a `DocumentsProvider` that refuses (a revoked
 * grant, a broken document) makes the walk skip this node alone.
 */
internal class SafFolderTreeEntry(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    private val isFile: Boolean = false,
) : TreeEntry {
    override val isDirectory: Boolean = !isFile

    /** The tree-scoped URI of a file node, so the grant carried by the tree covers it. */
    val fileUri: Uri by lazy { DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId) }

    override fun children(): List<TreeEntry> {
        if (isFile) return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        // A stable order keeps "the first N" the same folder items every time.
        val sortOrder = "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC"
        return resolver.query(childrenUri, projection, null, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val isFolder = cursor.getString(mimeIndex).orEmpty() == DOCUMENT_TYPE_DIRECTORY
                    // A virtual document cannot be opened as a file stream, so it is skipped.
                    val flags = if (flagsIndex >= 0) cursor.getInt(flagsIndex) else 0
                    if (!isFolder && flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                    add(SafFolderTreeEntry(resolver, treeUri, id, isFile = !isFolder))
                }
            }
        }.orEmpty()
    }
}

/** The files of one picked folder, flat, capped and named. */
internal data class SafFolderPickResult(
    val files: List<FolderPickedFile>,
    val truncated: Boolean,
)

/**
 * Flattens [treeUri] (an `ACTION_OPEN_DOCUMENT_TREE` result) into at most
 * [limit] files. A tree whose root cannot be listed yields an empty result, which
 * the panel reports as [FolderPickNotice.EMPTY] instead of failing the tap.
 */
internal fun collectSafFolderFiles(
    resolver: ContentResolver,
    treeUri: Uri,
    limit: Int = MAX_FOLDER_FILES,
): SafFolderPickResult {
    val root = SafFolderTreeEntry(resolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    // The walk already treats an unlistable root as empty, which is the notice the panel shows.
    val walk = collectFolderTreeFiles(root, limit)
    return SafFolderPickResult(
        files = walk.files.filterIsInstance<SafFolderTreeEntry>().map {
            FolderPickedFile(name = resolver.displayName(it.fileUri), uri = it.fileUri)
        },
        truncated = walk.truncated,
    )
}

private const val DOCUMENT_TYPE_DIRECTORY = "vnd.android.document/directory"
