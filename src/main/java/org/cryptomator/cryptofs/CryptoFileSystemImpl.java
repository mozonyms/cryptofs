/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptofs;

import org.cryptomator.cryptofs.CryptoPathMapper.CiphertextDirectory;
import org.cryptomator.cryptofs.attr.AttributeByNameProvider;
import org.cryptomator.cryptofs.attr.AttributeProvider;
import org.cryptomator.cryptofs.attr.AttributeViewProvider;
import org.cryptomator.cryptofs.attr.AttributeViewType;
import org.cryptomator.cryptofs.common.ArrayUtils;
import org.cryptomator.cryptofs.common.CiphertextFileType;
import org.cryptomator.cryptofs.common.DeletingFileVisitor;
import org.cryptomator.cryptofs.common.FinallyUtil;
import org.cryptomator.cryptofs.dir.CiphertextDirectoryDeleter;
import org.cryptomator.cryptofs.dir.DirectoryStreamFactory;
import org.cryptomator.cryptofs.fh.OpenCryptoFiles;
import org.cryptomator.cryptolib.api.Cryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.cryptomator.cryptofs.common.Constants.SEPARATOR;

@CryptoFileSystemScoped
class CryptoFileSystemImpl extends CryptoFileSystem {
	
	private final CryptoFileSystemProvider provider;
	private final CryptoFileSystems cryptoFileSystems;
	private final Path pathToVault;
	private final Cryptor cryptor;
	private final CryptoFileStore fileStore;
	private final CryptoFileSystemStats stats;
	private final CryptoPathMapper cryptoPathMapper;
	private final CryptoPathFactory cryptoPathFactory;
	private final PathMatcherFactory pathMatcherFactory;
	private final DirectoryStreamFactory directoryStreamFactory;
	private final DirectoryIdProvider dirIdProvider;
	private final AttributeProvider fileAttributeProvider;
	private final AttributeByNameProvider fileAttributeByNameProvider;
	private final AttributeViewProvider fileAttributeViewProvider;
	private final OpenCryptoFiles openCryptoFiles;
	private final Symlinks symlinks;
	private final FinallyUtil finallyUtil;
	private final CiphertextDirectoryDeleter ciphertextDirDeleter;
	private final ReadonlyFlag readonlyFlag;
	private final CryptoFileSystemProperties fileSystemProperties;

	private final CryptoPath rootPath;
	private final CryptoPath emptyPath;

	private volatile boolean open = true;

	@Inject
	public CryptoFileSystemImpl(CryptoFileSystemProvider provider, CryptoFileSystems cryptoFileSystems, @PathToVault Path pathToVault, Cryptor cryptor,
								CryptoFileStore fileStore, CryptoFileSystemStats stats, CryptoPathMapper cryptoPathMapper, CryptoPathFactory cryptoPathFactory,
								PathMatcherFactory pathMatcherFactory, DirectoryStreamFactory directoryStreamFactory, DirectoryIdProvider dirIdProvider,
								AttributeProvider fileAttributeProvider, AttributeByNameProvider fileAttributeByNameProvider, AttributeViewProvider fileAttributeViewProvider,
								OpenCryptoFiles openCryptoFiles, Symlinks symlinks, FinallyUtil finallyUtil, CiphertextDirectoryDeleter ciphertextDirDeleter, ReadonlyFlag readonlyFlag,
								CryptoFileSystemProperties fileSystemProperties, RootDirectoryInitializer rootDirectoryInitializer) {
		this.provider = provider;
		this.cryptoFileSystems = cryptoFileSystems;
		this.pathToVault = pathToVault;
		this.cryptor = cryptor;
		this.fileStore = fileStore;
		this.stats = stats;
		cryptoPathMapper.setNoShortNameEncryption(fileSystemProperties.noShortNameEncryption());
		this.cryptoPathMapper = cryptoPathMapper;
		this.cryptoPathFactory = cryptoPathFactory;
		this.pathMatcherFactory = pathMatcherFactory;
		this.directoryStreamFactory = directoryStreamFactory;
		this.dirIdProvider = dirIdProvider;
		this.fileAttributeProvider = fileAttributeProvider;
		this.fileAttributeByNameProvider = fileAttributeByNameProvider;
		this.fileAttributeViewProvider = fileAttributeViewProvider;
		this.openCryptoFiles = openCryptoFiles;
		this.symlinks = symlinks;
		this.finallyUtil = finallyUtil;
		this.ciphertextDirDeleter = ciphertextDirDeleter;
		this.readonlyFlag = readonlyFlag;
		this.fileSystemProperties = fileSystemProperties;

		this.rootPath = cryptoPathFactory.rootFor(this);
		this.emptyPath = cryptoPathFactory.emptyFor(this);

		rootDirectoryInitializer.initialize(rootPath);
	}

	@Override
	public Path getPathToVault() {
		return pathToVault;
	}

	@Override
	public CryptoFileSystemStats getStats() {
		return stats;
	}

	/* java.nio.file.FileSystem API */

	@Override
	public CryptoFileSystemProvider provider() {
		assertOpen();
		return provider;
	}

	@Override
	public boolean isReadOnly() {
		assertOpen();
		return readonlyFlag.isSet();
	}

	@Override
	public String getSeparator() {
		assertOpen();
		return SEPARATOR;
	}

	@Override
	public Iterable<Path> getRootDirectories() {
		assertOpen();
		return Collections.singleton(getRootPath());
	}

	@Override
	public Iterable<FileStore> getFileStores() {
		assertOpen();
		return Collections.singleton(fileStore);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void close() throws IOException {
		// TODO close watch services when implemented
		if (open) {
			open = false;
			finallyUtil.guaranteeInvocationOf( //
					() -> cryptoFileSystems.remove(this), //
					() -> openCryptoFiles.close(), //
					() -> directoryStreamFactory.close(), //
					() -> cryptor.destroy());
		}
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public Set<String> supportedFileAttributeViews() {
		assertOpen();
		return fileStore.supportedFileAttributeViewTypes().stream().map(AttributeViewType::getViewName).collect(Collectors.toSet());
	}

	@Override
	public CryptoPath getPath(String first, String... more) {
		assertOpen();
		return cryptoPathFactory.getPath(this, first, more);
	}

	@Override
	public PathMatcher getPathMatcher(String syntaxAndPattern) {
		assertOpen();
		return pathMatcherFactory.pathMatcherFrom(syntaxAndPattern);
	}

	@Override
	public UserPrincipalLookupService getUserPrincipalLookupService() {
		assertOpen();
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchService newWatchService() throws IOException {
		assertOpen();
		throw new UnsupportedOperationException();
	}

	/* methods delegated to by CryptoFileSystemProvider */

	void setAttribute(CryptoPath cleartextPath, String attribute, Object value, LinkOption... options) throws IOException {
		readonlyFlag.assertWritable();
		fileAttributeByNameProvider.setAttribute(cleartextPath, attribute, value, options);
	}

	Map<String, Object> readAttributes(CryptoPath cleartextPath, String attributes, LinkOption... options) throws IOException {
		return fileAttributeByNameProvider.readAttributes(cleartextPath, attributes, options);
	}

	<A extends BasicFileAttributes> A readAttributes(CryptoPath cleartextPath, Class<A> type, LinkOption... options) throws IOException {
		return fileAttributeProvider.readAttributes(cleartextPath, type, options);
	}

	/**
	 * @param cleartextPath the path to the file
	 * @param type          the Class object corresponding to the file attribute view
	 * @param options       future use
	 * @return a file attribute view of the specified type, or <code>null</code> if the attribute view type is not available
	 * @see AttributeViewProvider#getAttributeView(CryptoPath, Class, LinkOption...)
	 */
	<V extends FileAttributeView> V getFileAttributeView(CryptoPath cleartextPath, Class<V> type, LinkOption... options) {
		return fileAttributeViewProvider.getAttributeView(cleartextPath, type, options);
	}

	void checkAccess(CryptoPath cleartextPath, AccessMode... modes) throws IOException {
		if (fileStore.supportsFileAttributeView(PosixFileAttributeView.class)) {
			Set<PosixFilePermission> permissions = readAttributes(cleartextPath, PosixFileAttributes.class).permissions();
			boolean accessDenied = Arrays.stream(modes).anyMatch(accessMode -> !hasAccess(permissions, accessMode));
			if (accessDenied) {
				throw new AccessDeniedException(cleartextPath.toString());
			}
		} else if (fileStore.supportsFileAttributeView(DosFileAttributeView.class)) {
			DosFileAttributes attrs = readAttributes(cleartextPath, DosFileAttributes.class);
			if (ArrayUtils.contains(modes, AccessMode.WRITE) && attrs.isReadOnly()) {
				throw new AccessDeniedException(cleartextPath.toString(), null, "read only file");
			}
		} else {
			// read attributes to check for file existence / throws IOException if file does not exist
			readAttributes(cleartextPath, BasicFileAttributes.class);
		}
	}

	private boolean hasAccess(Set<PosixFilePermission> permissions, AccessMode accessMode) {
		switch (accessMode) {
			case READ:
				return permissions.contains(PosixFilePermission.OWNER_READ);
			case WRITE:
				return permissions.contains(PosixFilePermission.OWNER_WRITE);
			case EXECUTE:
				return permissions.contains(PosixFilePermission.OWNER_EXECUTE);
			default:
				throw new UnsupportedOperationException("AccessMode " + accessMode + " not supported.");
		}
	}

	boolean isHidden(CryptoPath cleartextPath) throws IOException {
		DosFileAttributeView view = this.getFileAttributeView(cleartextPath, DosFileAttributeView.class);
		if (view != null) {
			return view.readAttributes().isHidden();
		} else {
			return false;
		}
	}

	void createDirectory(CryptoPath cleartextDir, FileAttribute<?>... attrs) throws IOException {
		readonlyFlag.assertWritable();
		CryptoPath cleartextParentDir = cleartextDir.getParent();
		if (cleartextParentDir == null) {
			return;
		}
		Path ciphertextParentDir = cryptoPathMapper.getCiphertextDir(cleartextParentDir).path;
		if (!Files.exists(ciphertextParentDir)) {
			throw new NoSuchFileException(cleartextParentDir.toString());
		}
		cryptoPathMapper.assertNonExisting(cleartextDir);
		CiphertextFilePath ciphertextPath = cryptoPathMapper.getCiphertextFilePath(cleartextDir);
		Path ciphertextDirFile = ciphertextPath.getDirFilePath();
		assertCiphertextPathLengthMeetsLimitations(ciphertextDirFile);
		CiphertextDirectory ciphertextDir = cryptoPathMapper.getCiphertextDir(cleartextDir);
		// atomically check for FileAlreadyExists and create otherwise:
		Files.createDirectory(ciphertextPath.getRawPath());
		try (FileChannel channel = FileChannel.open(ciphertextDirFile, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attrs)) {
			channel.write(UTF_8.encode(ciphertextDir.dirId));
		}
		// create dir if and only if the dirFile has been created right now (not if it has been created before):
		try {
			Files.createDirectories(ciphertextDir.path);
			ciphertextPath.persistLongFileName();
		} catch (IOException e) {
			// make sure there is no orphan dir file:
			Files.delete(ciphertextDirFile);
			cryptoPathMapper.invalidatePathMapping(cleartextDir);
			dirIdProvider.delete(ciphertextDirFile);
			throw e;
		}
	}

	DirectoryStream<Path> newDirectoryStream(CryptoPath cleartextDir, Filter<? super Path> filter) throws IOException {
		return directoryStreamFactory.newDirectoryStream(cleartextDir, filter);
	}

	FileChannel newFileChannel(CryptoPath cleartextPath, Set<? extends OpenOption> optionsSet, FileAttribute<?>... attrs) throws IOException {
		EffectiveOpenOptions options = EffectiveOpenOptions.from(optionsSet, readonlyFlag);
		if (options.writable()) {
			readonlyFlag.assertWritable();
		}
		CiphertextFileType ciphertextFileType;
		try {
			ciphertextFileType = cryptoPathMapper.getCiphertextFileType(cleartextPath);
		} catch (NoSuchFileException e) {
			if (options.create() || options.createNew()) {
				ciphertextFileType = CiphertextFileType.FILE;
			} else {
				throw e;
			}
		}
		switch (ciphertextFileType) {
			case SYMLINK:
				if (options.noFollowLinks()) {
					throw new UnsupportedOperationException("Unsupported OpenOption LinkOption.NOFOLLOW_LINKS. Can not create file channel for symbolic link.");
				} else {
					CryptoPath resolvedPath = symlinks.resolveRecursively(cleartextPath);
					return newFileChannel(resolvedPath, options, attrs);
				}
			case FILE:
				return newFileChannel(cleartextPath, options, attrs);
			default:
				throw new UnsupportedOperationException("Can not create file channel for " + ciphertextFileType.name());
		}
	}

	private FileChannel newFileChannel(CryptoPath cleartextFilePath, EffectiveOpenOptions options, FileAttribute<?>... attrs) throws IOException {
		CiphertextFilePath ciphertextPath = cryptoPathMapper.getCiphertextFilePath(cleartextFilePath);
		Path ciphertextFilePath = ciphertextPath.getFilePath();
		assertCiphertextPathLengthMeetsLimitations(ciphertextFilePath);
		if (options.createNew() && openCryptoFiles.get(ciphertextFilePath).isPresent()) {
			throw new FileAlreadyExistsException(cleartextFilePath.toString());
		} else {
			if (ciphertextPath.isShortened() && options.createNew()) {
				Files.createDirectory(ciphertextPath.getRawPath()); // might throw FileAlreadyExists
			} else if (ciphertextPath.isShortened()) {
				Files.createDirectories(ciphertextPath.getRawPath()); // suppresses FileAlreadyExists
			}
			FileChannel ch = openCryptoFiles.getOrCreate(ciphertextFilePath).newFileChannel(options); // might throw FileAlreadyExists
			if (options.writable()) {
				ciphertextPath.persistLongFileName();
				stats.incrementAccessesWritten();
			}
			if (options.readable()) {
				stats.incrementAccessesRead();
			}
			return ch;
		}
	}

	void delete(CryptoPath cleartextPath) throws IOException {
		readonlyFlag.assertWritable();
		CiphertextFileType ciphertextFileType = cryptoPathMapper.getCiphertextFileType(cleartextPath);
		CiphertextFilePath ciphertextPath = cryptoPathMapper.getCiphertextFilePath(cleartextPath);
		switch (ciphertextFileType) {
			case DIRECTORY:
				deleteDirectory(cleartextPath, ciphertextPath);
				return;
			default:
				Files.walkFileTree(ciphertextPath.getRawPath(), DeletingFileVisitor.INSTANCE);
				return;
		}
	}

	private void deleteDirectory(CryptoPath cleartextPath, CiphertextFilePath ciphertextPath) throws IOException {
		Path ciphertextDir = cryptoPathMapper.getCiphertextDir(cleartextPath).path;
		Path ciphertextDirFile = ciphertextPath.getDirFilePath();
		try {
			ciphertextDirDeleter.deleteCiphertextDirIncludingNonCiphertextFiles(ciphertextDir, cleartextPath);
			Files.walkFileTree(ciphertextPath.getRawPath(), DeletingFileVisitor.INSTANCE);
			cryptoPathMapper.invalidatePathMapping(cleartextPath);
			dirIdProvider.delete(ciphertextDirFile);
		} catch (NoSuchFileException e) {
			// translate ciphertext path to cleartext path
			throw new NoSuchFileException(cleartextPath.toString());
		} catch (DirectoryNotEmptyException e) {
			// translate ciphertext path to cleartext path
			throw new DirectoryNotEmptyException(cleartextPath.toString());
		}
	}

	void copy(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption... options) throws IOException {
		readonlyFlag.assertWritable();
		if (cleartextSource.equals(cleartextTarget)) {
			return;
		}
		CiphertextFileType ciphertextFileType = cryptoPathMapper.getCiphertextFileType(cleartextSource);
		if (!ArrayUtils.contains(options, StandardCopyOption.REPLACE_EXISTING)) {
			cryptoPathMapper.assertNonExisting(cleartextTarget);
		}
		switch (ciphertextFileType) {
			case SYMLINK:
				copySymlink(cleartextSource, cleartextTarget, options);
				return;
			case FILE:
				copyFile(cleartextSource, cleartextTarget, options);
				return;
			case DIRECTORY:
				copyDirectory(cleartextSource, cleartextTarget, options);
				return;
			default:
				throw new UnsupportedOperationException("Unhandled node type " + ciphertextFileType);
		}
	}

	private void copySymlink(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		if (ArrayUtils.contains(options, LinkOption.NOFOLLOW_LINKS)) {
			CiphertextFilePath ciphertextSourceFile = cryptoPathMapper.getCiphertextFilePath(cleartextSource);
			CiphertextFilePath ciphertextTargetFile = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
			assertCiphertextPathLengthMeetsLimitations(ciphertextTargetFile.getSymlinkFilePath());
			CopyOption[] resolvedOptions = ArrayUtils.without(options, LinkOption.NOFOLLOW_LINKS).toArray(CopyOption[]::new);
			Files.createDirectories(ciphertextTargetFile.getRawPath());
			Files.copy(ciphertextSourceFile.getSymlinkFilePath(), ciphertextTargetFile.getSymlinkFilePath(), resolvedOptions);
			ciphertextTargetFile.persistLongFileName();
		} else {
			CryptoPath resolvedSource = symlinks.resolveRecursively(cleartextSource);
			CryptoPath resolvedTarget = symlinks.resolveRecursively(cleartextTarget);
			CopyOption[] resolvedOptions = ArrayUtils.with(options, LinkOption.NOFOLLOW_LINKS).toArray(CopyOption[]::new);
			copy(resolvedSource, resolvedTarget, resolvedOptions);
		}
	}

	private void copyFile(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		CiphertextFilePath ciphertextSource = cryptoPathMapper.getCiphertextFilePath(cleartextSource);
		CiphertextFilePath ciphertextTarget = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
		assertCiphertextPathLengthMeetsLimitations(ciphertextTarget.getFilePath());
		if (ciphertextTarget.isShortened()) {
			Files.createDirectories(ciphertextTarget.getRawPath());
		}
		Files.copy(ciphertextSource.getFilePath(), ciphertextTarget.getFilePath(), options);
		ciphertextTarget.persistLongFileName();
	}

	private void copyDirectory(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		// non-recursive as per contract:
		CiphertextFilePath ciphertextTarget = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
		if (Files.notExists(ciphertextTarget.getRawPath())) {
			// create new:
			createDirectory(cleartextTarget);
			ciphertextTarget.persistLongFileName();
		} else if (ArrayUtils.contains(options, StandardCopyOption.REPLACE_EXISTING)) {
			// keep existing (if empty):
			Path ciphertextTargetDir = cryptoPathMapper.getCiphertextDir(cleartextTarget).path;
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(ciphertextTargetDir)) {
				if (ds.iterator().hasNext()) {
					throw new DirectoryNotEmptyException(cleartextTarget.toString());
				}
			}
		} else {
			throw new FileAlreadyExistsException(cleartextTarget.toString(), null, "Ciphertext file already exists: " + ciphertextTarget);
		}
		if (ArrayUtils.contains(options, StandardCopyOption.COPY_ATTRIBUTES)) {
			Path ciphertextSourceDir = cryptoPathMapper.getCiphertextDir(cleartextSource).path;
			Path ciphertextTargetDir = cryptoPathMapper.getCiphertextDir(cleartextTarget).path;
			copyAttributes(ciphertextSourceDir, ciphertextTargetDir);
		}
	}

	private void copyAttributes(Path src, Path dst) throws IOException {
		Set<AttributeViewType> supportedAttributeViewTypes = fileStore.supportedFileAttributeViewTypes();
		if (supportedAttributeViewTypes.contains(AttributeViewType.BASIC)) {
			BasicFileAttributes srcAttrs = Files.readAttributes(src, BasicFileAttributes.class);
			BasicFileAttributeView dstAttrView = Files.getFileAttributeView(dst, BasicFileAttributeView.class);
			dstAttrView.setTimes(srcAttrs.lastModifiedTime(), srcAttrs.lastAccessTime(), srcAttrs.creationTime());
		}
		if (supportedAttributeViewTypes.contains(AttributeViewType.OWNER)) {
			FileOwnerAttributeView srcAttrView = Files.getFileAttributeView(src, FileOwnerAttributeView.class);
			FileOwnerAttributeView dstAttrView = Files.getFileAttributeView(dst, FileOwnerAttributeView.class);
			dstAttrView.setOwner(srcAttrView.getOwner());
		}
		if (supportedAttributeViewTypes.contains(AttributeViewType.POSIX)) {
			PosixFileAttributes srcAttrs = Files.readAttributes(src, PosixFileAttributes.class);
			PosixFileAttributeView dstAttrView = Files.getFileAttributeView(dst, PosixFileAttributeView.class);
			dstAttrView.setGroup(srcAttrs.group());
			dstAttrView.setPermissions(srcAttrs.permissions());
		}
		if (supportedAttributeViewTypes.contains(AttributeViewType.DOS)) {
			DosFileAttributes srcAttrs = Files.readAttributes(src, DosFileAttributes.class);
			DosFileAttributeView dstAttrView = Files.getFileAttributeView(dst, DosFileAttributeView.class);
			dstAttrView.setArchive(srcAttrs.isArchive());
			dstAttrView.setHidden(srcAttrs.isHidden());
			dstAttrView.setReadOnly(srcAttrs.isReadOnly());
			dstAttrView.setSystem(srcAttrs.isSystem());
		}
		// TODO: copy user attributes
	}

	void move(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption... options) throws IOException {
		readonlyFlag.assertWritable();
		if (cleartextSource.equals(cleartextTarget)) {
			return;
		}
		CiphertextFileType ciphertextFileType = cryptoPathMapper.getCiphertextFileType(cleartextSource);
		if (!ArrayUtils.contains(options, StandardCopyOption.REPLACE_EXISTING)) {
			cryptoPathMapper.assertNonExisting(cleartextTarget);
		}
		switch (ciphertextFileType) {
			case SYMLINK:
				moveSymlink(cleartextSource, cleartextTarget, options);
				return;
			case FILE:
				moveFile(cleartextSource, cleartextTarget, options);
				return;
			case DIRECTORY:
				moveDirectory(cleartextSource, cleartextTarget, options);
				return;
			default:
				throw new UnsupportedOperationException("Unhandled node type " + ciphertextFileType);
		}
	}

	private void moveSymlink(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		// according to Files.move() JavaDoc:
		// "the symbolic link itself, not the target of the link, is moved"
		CiphertextFilePath ciphertextSource = cryptoPathMapper.getCiphertextFilePath(cleartextSource);
		CiphertextFilePath ciphertextTarget = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
		assertCiphertextPathLengthMeetsLimitations(ciphertextTarget.getSymlinkFilePath());
		try (OpenCryptoFiles.TwoPhaseMove twoPhaseMove = openCryptoFiles.prepareMove(ciphertextSource.getRawPath(), ciphertextTarget.getRawPath())) {
			Files.move(ciphertextSource.getRawPath(), ciphertextTarget.getRawPath(), options);
			if (ciphertextTarget.isShortened()) {
				ciphertextTarget.persistLongFileName();
			} else {
				Files.deleteIfExists(ciphertextTarget.getInflatedNamePath()); // no longer needed if not shortened
			}
			twoPhaseMove.commit();
		}
	}

	private void moveFile(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		// While moving a file, it is possible to keep the channels open. In order to make this work
		// we need to re-map the OpenCryptoFile entry.
		CiphertextFilePath ciphertextSource = cryptoPathMapper.getCiphertextFilePath(cleartextSource);
		CiphertextFilePath ciphertextTarget = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
		assertCiphertextPathLengthMeetsLimitations(ciphertextTarget.getFilePath());
		try (OpenCryptoFiles.TwoPhaseMove twoPhaseMove = openCryptoFiles.prepareMove(ciphertextSource.getRawPath(), ciphertextTarget.getRawPath())) {
			if (ciphertextTarget.isShortened()) {
				Files.createDirectory(ciphertextTarget.getRawPath());
				ciphertextTarget.persistLongFileName();
			}
			Files.move(ciphertextSource.getFilePath(), ciphertextTarget.getFilePath(), options);
			if (ciphertextSource.isShortened()) {
				Files.walkFileTree(ciphertextSource.getRawPath(), DeletingFileVisitor.INSTANCE);
			}
			twoPhaseMove.commit();
		}
	}

	private void moveDirectory(CryptoPath cleartextSource, CryptoPath cleartextTarget, CopyOption[] options) throws IOException {
		// Since we only rename the directory file, all ciphertext paths of subresources stay the same.
		// Hence there is no need to re-map OpenCryptoFile entries.
		CiphertextFilePath ciphertextSource = cryptoPathMapper.getCiphertextFilePath(cleartextSource);
		CiphertextFilePath ciphertextTarget = cryptoPathMapper.getCiphertextFilePath(cleartextTarget);
		assertCiphertextPathLengthMeetsLimitations(ciphertextTarget.getDirFilePath());
		if (ArrayUtils.contains(options, StandardCopyOption.REPLACE_EXISTING)) {
			// check if not attempting to move atomically:
			if (ArrayUtils.contains(options, StandardCopyOption.ATOMIC_MOVE)) {
				throw new AtomicMoveNotSupportedException(cleartextSource.toString(), cleartextTarget.toString(), "Replacing directories during move requires non-atomic status checks.");
			}
			// check if dir is empty:
			Path oldCiphertextDir = cryptoPathMapper.getCiphertextDir(cleartextTarget).path;
			boolean oldCiphertextDirExists = true;
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(oldCiphertextDir)) {
				if (ds.iterator().hasNext()) {
					throw new DirectoryNotEmptyException(cleartextTarget.toString());
				}
			} catch (NoSuchFileException e) {
				oldCiphertextDirExists = false;
			}
			// cleanup dir to be replaced:
			if (oldCiphertextDirExists) {
				Files.walkFileTree(oldCiphertextDir, DeletingFileVisitor.INSTANCE);
			}
			Files.walkFileTree(ciphertextTarget.getRawPath(), DeletingFileVisitor.INSTANCE);
		}
		
		// no exceptions until this point, so MOVE:
		Files.move(ciphertextSource.getRawPath(), ciphertextTarget.getRawPath(), options);
		if (ciphertextTarget.isShortened()) {
			ciphertextTarget.persistLongFileName();
		} else {
			Files.deleteIfExists(ciphertextTarget.getInflatedNamePath()); // no longer needed if not shortened
		}
		dirIdProvider.move(ciphertextSource.getDirFilePath(), ciphertextTarget.getDirFilePath());
		cryptoPathMapper.movePathMapping(cleartextSource, cleartextTarget);
	}

	CryptoFileStore getFileStore() {
		return fileStore;
	}

	void createSymbolicLink(CryptoPath cleartextPath, Path target, FileAttribute<?>... attrs) throws IOException {
		assertOpen();
		CiphertextFilePath ciphertextFilePath = cryptoPathMapper.getCiphertextFilePath(cleartextPath);
		assertCiphertextPathLengthMeetsLimitations(ciphertextFilePath.getSymlinkFilePath());
		symlinks.createSymbolicLink(cleartextPath, target, attrs);
	}

	CryptoPath readSymbolicLink(CryptoPath cleartextPath) throws IOException {
		assertOpen();
		return symlinks.readSymbolicLink(cleartextPath);
	}

	/* internal methods */

	CryptoPath getRootPath() {
		return rootPath;
	}

	CryptoPath getEmptyPath() {
		return emptyPath;
	}
	
	void assertCiphertextPathLengthMeetsLimitations(Path cdrFilePath) throws FileNameTooLongException {
		Path vaultRelativePath = pathToVault.relativize(cdrFilePath);
		String fileName = vaultRelativePath.getName(3).toString(); // fourth path element (d/xx/yyyyy/file.c9r/symlink.c9r)
		String path = vaultRelativePath.toString();
		if (fileName.length() > fileSystemProperties.maxNameLength() || path.length() > fileSystemProperties.maxPathLength()) {
			throw new FileNameTooLongException(path, fileSystemProperties.maxPathLength(), fileSystemProperties.maxNameLength());
		}
	}

	void assertOpen() {
		if (!open) {
			throw new ClosedFileSystemException();
		}
	}

	@Override
	public String toString() {
		return format("%sCryptoFileSystem(%s)", open ? "" : "closed ", pathToVault);
	}

}
