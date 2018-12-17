/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptofs;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.UserPrincipal;

@PerAttributeView
class CryptoFileOwnerAttributeView extends AbstractCryptoFileAttributeView implements FileOwnerAttributeView {

	private final ReadonlyFlag readonlyFlag;

	@Inject
	public CryptoFileOwnerAttributeView(CryptoPath cleartextPath, CryptoPathMapper pathMapper, OpenCryptoFiles openCryptoFiles,  ReadonlyFlag readonlyFlag) {
		super(cleartextPath, pathMapper, openCryptoFiles);
		this.readonlyFlag = readonlyFlag;
	}

	@Override
	public String name() {
		return "owner";
	}

	@Override
	public UserPrincipal getOwner() throws IOException {
		return getCiphertextAttributeView(FileOwnerAttributeView.class).getOwner();
	}

	@Override
	public void setOwner(UserPrincipal owner) throws IOException {
		readonlyFlag.assertWritable();
		getCiphertextAttributeView(FileOwnerAttributeView.class).setOwner(owner);
	}

}
