// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.ftp

import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.usermanager.impl.WriteRequest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * Apache FtpServer 1.2 のデフォルト [NativeFileSystemFactory] (java.io.File) を
 * NIO ([java.nio.file.Files]) ベースに置き換える独自実装。
 *
 * 動機: P7 第7弾 #1 で FTP からの書き込みが失敗するという実機報告があり、
 *   - java.io.File.canWrite() / RandomAccessFile が Android scoped storage で
 *     不安定 (env による)、
 *   - SFTP (Apache MINA SSHD) は VirtualFileSystemFactory ＝ NIO Path 経由で
 *     動作しているので、FTP も NIO に揃えて切り分け可能性をつぶす、
 * の 2 点。
 *
 * 仮想パスは「user の homeDirectory 以下にチャ root された FTP 仮想 FS」を提供し、
 * CWD 始点は "/"。getFile("/x/y") は homeDirectory.resolve("x/y") に解決される。
 * シンボリックリンク追跡を含めて homeDirectory の外には決して出さない (path traversal 防御)。
 */
internal class NioFileSystemFactory(
    private val rootPath: Path,
) : FileSystemFactory {

    override fun createFileSystemView(user: User): FileSystemView {
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.createDirectories(rootPath) }
        }
        return NioFileSystemView(rootPath.toAbsolutePath().normalize(), user)
    }
}

private class NioFileSystemView(
    private val rootDir: Path,
    private val user: User,
) : FileSystemView {
    private var currentVirtual: String = "/"

    override fun getHomeDirectory(): FtpFile = NioFtpFile("/", rootDir, user)

    override fun getWorkingDirectory(): FtpFile {
        val phys = resolve(currentVirtual)
        return NioFtpFile(currentVirtual, phys, user)
    }

    override fun changeWorkingDirectory(dir: String?): Boolean {
        val target = normalizeVirtual(currentVirtual, dir ?: "/")
        val phys = resolve(target)
        if (!Files.isDirectory(phys)) return false
        currentVirtual = target
        return true
    }

    override fun getFile(file: String?): FtpFile {
        val virtual = normalizeVirtual(currentVirtual, file ?: ".")
        val phys = resolve(virtual)
        return NioFtpFile(virtual, phys, user)
    }

    override fun isRandomAccessible(): Boolean = true

    override fun dispose() {
        // no-op
    }

    private fun resolve(virtual: String): Path {
        val rel = virtual.trimStart('/')
        val resolved = if (rel.isEmpty()) rootDir else rootDir.resolve(rel).normalize()
        // path traversal 防御: 解決後のパスが rootDir 配下でなければ rootDir そのものを返す。
        return if (resolved.startsWith(rootDir)) resolved else rootDir
    }

    private fun normalizeVirtual(base: String, given: String): String {
        val combined = if (given.startsWith("/")) given else "$base/$given"
        val parts = ArrayDeque<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }
}

/**
 * NIO Path で実装された [FtpFile]。java.io.File を一切使わない。
 * 失敗時の詳細は Logcat (TAG=NioFtpFile) に流して、原因切り分けに使えるようにする。
 */
private class NioFtpFile(
    private val virtualPath: String,
    private val physical: Path,
    private val user: User,
) : FtpFile {

    override fun getAbsolutePath(): String = virtualPath

    override fun getName(): String = if (virtualPath == "/") "/" else virtualPath.substringAfterLast('/')

    override fun isHidden(): Boolean = runCatching { Files.isHidden(physical) }.getOrDefault(false)

    override fun isDirectory(): Boolean = Files.isDirectory(physical)

    override fun isFile(): Boolean = Files.isRegularFile(physical)

    override fun doesExist(): Boolean = Files.exists(physical)

    override fun getSize(): Long = runCatching { Files.size(physical) }.getOrDefault(0L)

    override fun getOwnerName(): String = "user"
    override fun getGroupName(): String = "group"
    override fun getLinkCount(): Int = if (Files.isDirectory(physical)) 3 else 1

    override fun getLastModified(): Long =
        runCatching { Files.getLastModifiedTime(physical).toMillis() }.getOrDefault(0L)

    override fun setLastModified(time: Long): Boolean = runCatching {
        Files.setLastModifiedTime(physical, FileTime.fromMillis(time)); true
    }.getOrDefault(false)

    override fun isReadable(): Boolean = Files.isReadable(physical)

    override fun isWritable(): Boolean {
        // WritePermission Authority がない (readOnly=true 等) なら即 false。
        val authority: Authority? = user.authorities?.firstOrNull { it.canAuthorize(WriteRequest(virtualPath)) }
        if (authority?.authorize(WriteRequest(virtualPath)) == null) return false
        // 存在するなら NIO の isWritable、しないなら親フォルダの書き込み可否。
        return if (Files.exists(physical)) {
            Files.isWritable(physical)
        } else {
            val parent = physical.parent ?: return false
            Files.isDirectory(parent) && Files.isWritable(parent)
        }
    }

    override fun isRemovable(): Boolean {
        if (virtualPath == "/") return false
        val parent = physical.parent ?: return false
        return Files.isWritable(parent)
    }

    override fun delete(): Boolean = runCatching {
        if (!isRemovable) return@runCatching false
        if (Files.isDirectory(physical)) Files.delete(physical) else Files.deleteIfExists(physical)
        true
    }.getOrElse {
        android.util.Log.w(TAG, "delete failed for $physical", it); false
    }

    override fun move(destination: FtpFile?): Boolean {
        val dst = (destination as? NioFtpFile)?.physical ?: return false
        return runCatching {
            Files.move(physical, dst, StandardCopyOption.REPLACE_EXISTING); true
        }.getOrElse {
            android.util.Log.w(TAG, "move failed $physical -> $dst", it); false
        }
    }

    override fun mkdir(): Boolean = runCatching {
        if (!isWritable) return@runCatching false
        Files.createDirectories(physical); true
    }.getOrElse {
        android.util.Log.w(TAG, "mkdir failed for $physical", it); false
    }

    override fun listFiles(): List<FtpFile> = runCatching {
        if (!Files.isDirectory(physical)) return@runCatching emptyList()
        Files.newDirectoryStream(physical).use { stream ->
            stream.map { child ->
                val childVirtual = if (virtualPath == "/") "/${child.fileName}"
                else "$virtualPath/${child.fileName}"
                NioFtpFile(childVirtual, child, user) as FtpFile
            }
        }
    }.getOrElse { emptyList() }

    override fun createOutputStream(offset: Long): OutputStream {
        if (!isWritable) throw IOException("No write permission: $virtualPath")
        // 親ディレクトリが無ければ作る (createNewFile 時の堅牢化)。
        val parent = physical.parent
        if (parent != null && !Files.isDirectory(parent)) {
            runCatching { Files.createDirectories(parent) }
        }
        val opts = if (offset > 0L) {
            arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        } else {
            arrayOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
        val channel = try {
            Files.newByteChannel(physical, *opts)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "newByteChannel WRITE failed for $physical (offset=$offset)", t)
            throw IOException("openOutput failed: ${t.message}", t)
        }
        if (offset > 0L) {
            runCatching { channel.position(offset) }.onFailure {
                android.util.Log.w(TAG, "seek failed: $physical -> $offset", it)
            }
        }
        return java.nio.channels.Channels.newOutputStream(channel)
    }

    override fun createInputStream(offset: Long): InputStream {
        if (!isReadable) throw IOException("No read permission: $virtualPath")
        val channel = try {
            Files.newByteChannel(physical, StandardOpenOption.READ)
        } catch (e: NoSuchFileException) {
            throw IOException("Not found: $virtualPath", e)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "newByteChannel READ failed for $physical", t)
            throw IOException("openInput failed: ${t.message}", t)
        }
        if (offset > 0L) {
            runCatching { channel.position(offset) }
        }
        return java.nio.channels.Channels.newInputStream(channel)
    }

    override fun getPhysicalFile(): Any = physical

    override fun equals(other: Any?): Boolean = other is NioFtpFile && other.physical == physical
    override fun hashCode(): Int = physical.hashCode()

    private companion object {
        const val TAG = "NioFtpFile"
    }
}
