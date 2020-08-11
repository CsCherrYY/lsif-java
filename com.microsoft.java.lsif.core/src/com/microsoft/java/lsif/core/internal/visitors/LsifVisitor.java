/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

package com.microsoft.java.lsif.core.internal.visitors;

import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.internal.compiler.classfmt.ModuleInfo;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.core.BinaryModule;
import org.eclipse.jdt.internal.core.BinaryType;
import org.eclipse.jdt.internal.core.ClassFile;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.JrtPackageFragmentRoot;
import org.eclipse.jdt.internal.core.LocalVariable;
import org.eclipse.jdt.internal.core.ResolvedBinaryField;
import org.eclipse.jdt.internal.core.ResolvedBinaryMethod;
import org.eclipse.jdt.internal.core.ResolvedBinaryType;
import org.eclipse.jdt.internal.core.SourceField;
import org.eclipse.jdt.internal.core.SourceMethod;
import org.eclipse.jdt.internal.core.SourceType;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import com.microsoft.java.lsif.core.internal.JdtlsUtils;
import com.microsoft.java.lsif.core.internal.LanguageServerIndexerPlugin;
import com.microsoft.java.lsif.core.internal.indexer.IndexerContext;
import com.microsoft.java.lsif.core.internal.indexer.LsifService;
import com.microsoft.java.lsif.core.internal.indexer.Repository;
import com.microsoft.java.lsif.core.internal.indexer.Indexer.ProjectBuildTool;
import com.microsoft.java.lsif.core.internal.protocol.Document;
import com.microsoft.java.lsif.core.internal.protocol.Project;
import com.microsoft.java.lsif.core.internal.protocol.Range;
import com.microsoft.java.lsif.core.internal.protocol.ResultSet;
import com.microsoft.java.lsif.core.internal.protocol.Moniker.MonikerKind;
import com.microsoft.java.lsif.core.internal.protocol.PackageInformation.PackageManager;

public class LsifVisitor extends ProtocolVisitor {

	private ProjectBuildTool builder;

	private boolean isPublish;

	public LsifVisitor(LsifService lsif, IndexerContext context, ProjectBuildTool builder, boolean isPublish) {
		super(lsif, context);
		this.builder = builder;
		this.isPublish = isPublish;
	}

	@Override
	public boolean visit(SimpleName node) {
		resolve(node.getStartPosition(), node.getLength(), isTypeOrMethodDeclaration(node), true, MonikerKind.IMPORT);
		return false;
	}

	@Override
	public boolean visit(SingleVariableDeclaration node) {
		MonikerKind monikerKind = (node.getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
		resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		return true;
	}

	@Override
	public boolean visit(EnumDeclaration node) {
		MonikerKind monikerKind = (node.getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
		resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		return true;
	}

	@Override
	public boolean visit(EnumConstantDeclaration node) {
		MonikerKind monikerKind = MonikerKind.EXPORT; // All the enum values are modified by `public static final`
		resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		return true;
	}

	@Override
	public boolean visit(TypeDeclaration node) {
		MonikerKind monikerKind = (node.getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
		resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		return true;
	}

	@Override
	public boolean visit(MethodDeclaration node) {
		MonikerKind monikerKind = (node.getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
		resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		return true;
	}

	@Override
	public boolean visit(VariableDeclarationFragment node) {
		ASTNode parent = node.getParent();
		if (parent instanceof VariableDeclarationStatement) {
			MonikerKind monikerKind = (((VariableDeclarationStatement) parent).getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
			resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		} else if (parent instanceof FieldDeclaration) {
			MonikerKind monikerKind = (((FieldDeclaration) parent).getModifiers() & Modifier.PUBLIC) > 0 ? MonikerKind.EXPORT : MonikerKind.LOCAL;
			resolve(node.getName().getStartPosition(), node.getName().getLength(), false, true, monikerKind);
		}
		return true;
	}

	@Override
	public boolean visit(SimpleType node) {
		resolve(node.getStartPosition(), node.getLength(), isTypeOrMethodDeclaration(node), true, MonikerKind.IMPORT);
		return false;
	}

	private void resolve(int startPosition, int length, boolean needResolveImpl, boolean hasMoniker, MonikerKind monikerKind) {
		try {
			org.eclipse.lsp4j.Range sourceLspRange = JDTUtils.toRange(this.getContext().getCompilationUnit().getTypeRoot(),
					startPosition, length);

			IJavaElement element = JDTUtils.findElementAtSelection(this.getContext().getCompilationUnit().getTypeRoot(),
					sourceLspRange.getStart().getLine(), sourceLspRange.getStart().getCharacter(), new PreferenceManager(),
					new NullProgressMonitor());
			if (element == null) {
				return;
			}

			LsifService lsif = this.getLsif();
			Document docVertex = this.getContext().getDocVertex();
			Project projVertex = this.getContext().getProjVertex();
			Range sourceRange = Repository.getInstance().enlistRange(lsif, docVertex, sourceLspRange);

			Location definitionLocation = JdtlsUtils.getElementLocation(element);
			if (definitionLocation == null) {
				// no target location, only resolve hover.
				Hover hover = VisitorUtils.resolveHoverInformation(docVertex, sourceRange.getStart().getLine(),
						sourceRange.getStart().getCharacter());
				if (VisitorUtils.isEmptyHover(hover)) {
					return;
				}
				ResultSet resultSet = VisitorUtils.ensureResultSet(lsif, sourceRange);
				VisitorUtils.emitHoverResult(hover, lsif, resultSet);
				return;
			}

			// Import Monikers
			IJavaProject javaproject = element.getJavaProject();
			ICompilationUnit compilationUnit = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
			IClassFile cf = (IClassFile) element.getAncestor(IJavaElement.CLASS_FILE);
			PackageManager manager = null;
			String groupId = "";
			String artifactId = "";
			String version = "";
			String url = "";
			if (compilationUnit == null && cf != null) {
				IPath path = cf.getPath();
				IPackageFragmentRoot root = javaproject.findPackageFragmentRoot(path);
				if (root instanceof JrtPackageFragmentRoot) {
					Manifest manifest = ((JrtPackageFragmentRoot)root).getManifest();
					Attributes attributes = manifest.getMainAttributes();
					String ver = attributes.getValue("Implementation-Version");
					if (ver != null) {
						version = ver;
					}
					IModuleDescription moduleDescription = ((JrtPackageFragmentRoot)root).getModuleDescription();
					if (moduleDescription instanceof BinaryModule) {
						groupId = ((BinaryModule)moduleDescription).getElementName();
					}
					manager = PackageManager.JDK;
				} else if (root instanceof JarPackageFragmentRoot) {
					List<File> fileList = new ArrayList<>();
					if (builder == ProjectBuildTool.MAVEN) {
						findPomFile(path.removeLastSegments(1).toFile(), fileList);
					} else if (builder == ProjectBuildTool.GRADLE) {
						findPomFile(path.removeLastSegments(2).toFile(), fileList);
					}
					if (fileList.size() > 0) {
						manager = (builder == ProjectBuildTool.MAVEN) ? PackageManager.MAVEN : PackageManager.GRADLE;
						File file = fileList.get(0);
						MavenXpp3Reader reader = new MavenXpp3Reader();
						Model model = reader.read(new FileReader(file.getAbsolutePath()));
						groupId = model.getGroupId();
						artifactId = model.getArtifactId();
						version = model.getVersion();
						Scm scm = model.getScm();
						if (scm != null) {
							url = scm.getUrl();
						}
					}
				}
			}
			String schemeId = "";
			if (manager == PackageManager.MAVEN || manager == PackageManager.GRADLE) {
				schemeId = groupId + "/" + artifactId;
			} else if (manager == PackageManager.JDK) {
				if (cf instanceof ClassFile) {
					schemeId = cf.getParent().getElementName() + "." + ((ClassFile) cf).getName();
				} else {
					schemeId = cf.getParent().getElementName() + "." + cf.getElementName();
				}
			}

			// Export Monikers
			if (monikerKind == MonikerKind.EXPORT && this.isPublish) {
				if (builder == ProjectBuildTool.MAVEN) {
					manager = PackageManager.MAVEN;
				} else if (builder == ProjectBuildTool.GRADLE) {
					manager = PackageManager.GRADLE;
				}
			}

			String id = VisitorUtils.createSymbolKey(definitionLocation);
			Document definitionDocument = Repository.getInstance().enlistDocument(lsif, definitionLocation.getUri(), projVertex);
			SymbolData symbolData = Repository.getInstance().enlistSymbolData(id, definitionDocument, projVertex);
			/* Ensure resultSet */
			symbolData.ensureResultSet(lsif, sourceRange);
			if (hasMoniker) {
				String identifier = "";
				try {
					identifier = this.getMonikerIdentifier(element);
				} catch (JavaModelException e) {
					// Do nothing
				}
			/* Generate Moniker */
				if (monikerKind == MonikerKind.EXPORT) {
					symbolData.generateMonikerExport(lsif, sourceRange, identifier, manager, javaproject);
				} else if (monikerKind == MonikerKind.LOCAL) {
					symbolData.generateMonikerLocal(lsif, sourceRange, identifier);
				} else if (definitionLocation.getUri().startsWith("jdt")) {
					symbolData.generateMonikerImport(lsif, sourceRange, identifier, schemeId, manager, version, url);
				}
				return;
			}
			/* Resolve definition */
			symbolData.resolveDefinition(lsif, definitionLocation);

			/* Resolve typeDefinition */
			symbolData.resolveTypeDefinition(lsif, docVertex, sourceLspRange);

			/* Resolve implementation */
			if (needResolveImpl) {
				symbolData.resolveImplementation(lsif, docVertex, sourceLspRange);
			}

			/* Resolve reference */
			symbolData.resolveReference(lsif, docVertex, definitionLocation, sourceRange);

			/* Resolve hover */
			symbolData.resolveHover(lsif, docVertex, sourceLspRange);
		} catch (Throwable ex) {
			LanguageServerIndexerPlugin.logException("Exception when dumping definition information ", ex);
		}
	}

	private String getMonikerIdentifier(IJavaElement element) throws JavaModelException {
		String identifier = element.getElementName();
		if (element instanceof SourceType) {
			return ((SourceType) element).getFullyQualifiedName();
		} else if (element instanceof BinaryType) {
			return ((BinaryType) element).getFullyQualifiedName();
		} else if (element instanceof SourceField) {
			return getMonikerIdentifier(element.getParent()) + "/" + identifier;
		} else if (element instanceof SourceMethod) {
			return getMonikerIdentifier(element.getParent()) + "/"
					+ identifier + ":" + ((SourceMethod) element).getSignature();
		} else if (element instanceof LocalVariable) {
			return getMonikerIdentifier(element.getParent()) + "/" + identifier;
		} else if (element instanceof ResolvedBinaryType) {
			return ((ResolvedBinaryType) element).getFullyQualifiedName();
		} else if (element instanceof ResolvedBinaryMethod) {
			return getMonikerIdentifier(element.getParent()) + "/"
					+ identifier + ":" + ((ResolvedBinaryMethod) element).getSignature();
		} else if (element instanceof ResolvedBinaryField) {
			return getMonikerIdentifier(element.getParent()) + "/" + identifier;
		}
		return identifier;
	}

	private boolean isTypeOrMethodDeclaration(ASTNode node) {
		return node.getParent() instanceof TypeDeclaration || node.getParent() instanceof MethodDeclaration;
	}

	private static void findPomFile(File file, List<File> fileList) {
		File[] files = file.listFiles();
		for (File subfile: files) {
			if (subfile.isDirectory()) {
				findPomFile(subfile, fileList);
			} else if (subfile.getName().endsWith(".pom")){
				fileList.add(subfile);
			}
		}
	}
}
