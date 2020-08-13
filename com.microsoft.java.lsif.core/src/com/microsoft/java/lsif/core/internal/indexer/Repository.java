/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

package com.microsoft.java.lsif.core.internal.indexer;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.microsoft.java.lsif.core.internal.LsifUtils;
import com.microsoft.java.lsif.core.internal.emitter.LsifEmitter;
import com.microsoft.java.lsif.core.internal.protocol.Document;
import com.microsoft.java.lsif.core.internal.protocol.Event;
import com.microsoft.java.lsif.core.internal.protocol.PackageInformation;
import com.microsoft.java.lsif.core.internal.protocol.Project;
import com.microsoft.java.lsif.core.internal.protocol.Range;
import com.microsoft.java.lsif.core.internal.protocol.PackageInformation.PackageManager;
import com.microsoft.java.lsif.core.internal.visitors.SymbolData;

public class Repository {

	// Key: document URI
	// Value: Document object
	private Map<String, Document> documentMap = new ConcurrentHashMap<>();

	// Key: document URI
	// Value: ranges among the documents
	// Key: LSP range
	// LSIF: range
	private Map<String, Map<org.eclipse.lsp4j.Range, Range>> rangeMap = new ConcurrentHashMap<>();

	// Key: definition meta-data id
	// Value: SymbolData
	private Map<String, SymbolData> symbolDataMap = new ConcurrentHashMap<>();

	// Key: documentURI
	// Value: Document object
	private Map<String, Document> beginededDocumentMap = new ConcurrentHashMap<>();

	// Key: groupId + artifactId
	// Value: PackageInformation
	private Map<String, PackageInformation> importPackageInformationMap = new ConcurrentHashMap<>();

	// Key: IJavaProject.getPath()
	// Value: PackageInformation
	private Map<String, PackageInformation> exportPackageInformationMap = new ConcurrentHashMap<>();

	// Key: groupId + artifactId
	// Value: isEmitted or not
	private Map<String, Boolean> packageInformationEmittedMap = new ConcurrentHashMap<>();

	private Repository() {
	}

	private static class RepositoryHolder {
		private static final Repository INSTANCE = new Repository();
	}

	public static Repository getInstance() {
		return RepositoryHolder.INSTANCE;
	}

	public synchronized Document enlistDocument(LsifService service, String uri, Project projVertex) {
		uri = LsifUtils.normalizeUri(uri);
		Document targetDocument = findDocumentByUri(uri);
		if (targetDocument == null) {
			targetDocument = service.getVertexBuilder().document(uri);
			addDocument(targetDocument);
			LsifEmitter.getInstance().emit(targetDocument);
			addToBeginededDocuments(targetDocument);
			LsifEmitter.getInstance()
					.emit(service.getVertexBuilder().event(Event.EventScope.DOCUMENT, Event.EventKind.BEGIN,
							targetDocument.getId()));
			LsifEmitter.getInstance().emit(service.getEdgeBuilder().contains(projVertex, targetDocument));
		}

		return targetDocument;
	}

	public synchronized Range enlistRange(LsifService service, Document docVertex,
			org.eclipse.lsp4j.Range lspRange) {
		Range range = findRange(docVertex.getUri(), lspRange);
		if (range == null) {
			range = service.getVertexBuilder().range(lspRange);
			addRange(docVertex, lspRange, range);
			LsifEmitter.getInstance().emit(range);
			LsifEmitter.getInstance().emit(service.getEdgeBuilder().contains(docVertex, range));
		}
		return range;
	}

	public Range enlistRange(LsifService service, String uri, org.eclipse.lsp4j.Range lspRange, Project projVertex) {
		return enlistRange(service, enlistDocument(service, uri, projVertex), lspRange);
	}

	public synchronized SymbolData enlistSymbolData(String id, Document docVertex, Project projVertex) {
		SymbolData symbolData = findSymbolDataById(id);
		if (symbolData == null) {
			symbolData = new SymbolData(projVertex, docVertex);
			addSymbolData(id, symbolData);
		}
		return symbolData;
	}

	public synchronized PackageInformation enlistImportPackageInformation(LsifService lsif, String id,
			String name, PackageManager manager, String version, String url) {
		PackageInformation packageInformation = findImportPackageInformationById(id);
		if (packageInformation == null) {
			if (manager == PackageManager.MAVEN || manager == PackageManager.GRADLE) {
				packageInformation = lsif.getVertexBuilder().packageInformation(name, manager, version, "git", url);
			} else if (manager == PackageManager.JDK) {
				packageInformation = lsif.getVertexBuilder().packageInformation(name, manager, version);
			} else {
				return packageInformation;
			}
			addImportPackageInformation(id, packageInformation);
		}
		return packageInformation;
	}

	public synchronized PackageInformation enlistExportPackageInformation(LsifService lsif, String id, String name,
			PackageManager manager, String version, String url) {
		PackageInformation packageInformation = findExportPackageInformationById(id);
		if (packageInformation == null) {
			if (manager == PackageManager.MAVEN) {
				packageInformation = lsif.getVertexBuilder().packageInformation(name, manager, version, "git", url);
			} else if (manager == PackageManager.GRADLE) {
				packageInformation = lsif.getVertexBuilder().packageInformation(name, manager, version);
			} else {
				return packageInformation;
			}
			addExportPackageInformation(id, packageInformation);
		}
		return packageInformation;
	}

	public synchronized boolean enlistPackageInformationEmitted(String id) {
		Boolean result = findPackageInformationEmittedById(id);
		if (result == null) {
			addPackageInformationEmitted(id);
			return false;
		} else {
			return true;
		}
	}

	public void addToBeginededDocuments(Document doc) {
		this.beginededDocumentMap.put(doc.getUri(), doc);
	}

	public void removeFromBeginededDocuments(String uri) {
		this.beginededDocumentMap.remove(uri);
	}

	public ArrayList<Document> getAllBeginededDocuments() {
		return new ArrayList<>(this.documentMap.values());
	}

	private void addDocument(Document doc) {
		this.documentMap.put(doc.getUri(), doc);
	}

	private void addRange(Document owner, org.eclipse.lsp4j.Range lspRange, Range range) {
		Map<org.eclipse.lsp4j.Range, Range> ranges = this.rangeMap.computeIfAbsent(owner.getUri(),
				s -> new ConcurrentHashMap<>());
		ranges.putIfAbsent(lspRange, range);
	}

	private void addSymbolData(String id, SymbolData symbolData) {
		this.symbolDataMap.put(id, symbolData);
	}

	private Document findDocumentByUri(String uri) {
		return this.documentMap.getOrDefault(uri, null);
	}

	private Range findRange(String uri, org.eclipse.lsp4j.Range lspRange) {
		Map<org.eclipse.lsp4j.Range, Range> ranges = rangeMap.get(uri);
		if (ranges != null) {
			return ranges.get(lspRange);
		}
		return null;
	}

	private SymbolData findSymbolDataById(String id) {
		return this.symbolDataMap.getOrDefault(id, null);
	}

	private PackageInformation findImportPackageInformationById(String id) {
		return this.importPackageInformationMap.getOrDefault(id, null);
	}

	private void addImportPackageInformation(String id, PackageInformation packageInformation) {
		this.importPackageInformationMap.put(id, packageInformation);
	}

	private PackageInformation findExportPackageInformationById(String id) {
		return this.exportPackageInformationMap.getOrDefault(id, null);
	}

	private void addExportPackageInformation(String id, PackageInformation packageInformation) {
		this.exportPackageInformationMap.put(id, packageInformation);
	}

	private Boolean findPackageInformationEmittedById(String id) {
		return this.packageInformationEmittedMap.getOrDefault(id, null);
	}

	private void addPackageInformationEmitted(String id) {
		this.packageInformationEmittedMap.put(id, Boolean.valueOf(true));
	}
}
