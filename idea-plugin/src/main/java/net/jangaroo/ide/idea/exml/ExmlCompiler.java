package net.jangaroo.ide.idea.exml;

import com.intellij.compiler.impl.javaCompiler.OutputItemImpl;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.IntermediateOutputCompiler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleFileIndex;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import net.jangaroo.exml.ExmlConstants;
import net.jangaroo.exml.compiler.Exmlc;
import net.jangaroo.exml.config.ExmlConfiguration;
import net.jangaroo.ide.idea.AbstractCompiler;
import net.jangaroo.ide.idea.util.OutputSinkItem;
import net.jangaroo.jooc.Jooc;
import net.jangaroo.utils.log.LogHandler;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 *
 */
public class ExmlCompiler extends AbstractCompiler implements IntermediateOutputCompiler {

  public ExmlCompiler() {
    super();
  }

  @NotNull
  public String getDescription() {
    return "EXML Compiler";
  }

  public boolean isCompilableFile(VirtualFile file, CompileContext context) {
    if (ExmlConstants.EXML_SUFFIX.equals("." + file.getExtension())) {
      Module module = context.getModuleByFile(file);
      if (module != null && FacetManager.getInstance(module).getFacetByType(ExmlFacetType.ID) != null) {
        return true;
      }
    }
    return false;
  }

  public static ExmlcConfigurationBean getExmlConfig(Module module) {
    ExmlFacet exmlFacet = FacetManager.getInstance(module).getFacetByType(ExmlFacetType.ID);
    return exmlFacet == null ? null : exmlFacet.getConfiguration().getState();
  }

  static String getXsdFilename(Module module) {
    if (module != null) {
      ExmlcConfigurationBean exmlcConfig = getExmlConfig(module);
      if (exmlcConfig != null) {
        return exmlcConfig.getGeneratedResourcesDirectory() + "/" + exmlcConfig.getXsd();
      }
    }
    return null;
  }

  private void addModuleDependenciesToComponentSuiteRegistry(Module module) {
    // Add all dependent component suites to component suite registry, so they are found when looking for some xtype of fullClassName:
    //System.out.println("Scanning dependencies of " + moduleName + " for component suite XSDs...");
    OrderEntry[] orderEntries = ModuleRootManager.getInstance(module).getOrderEntries();
    for (OrderEntry orderEntry : orderEntries) {
      InputStream xsdInputStream = null;
      try {
        if (orderEntry instanceof ModuleOrderEntry) {
          String xsdFilename = getXsdFilename(((ModuleOrderEntry)orderEntry).getModule());
          if (xsdFilename != null) {
            xsdInputStream = new FileInputStream(xsdFilename);
          }
        } else {
          String zipFileName = findDependentModuleZipFileName(orderEntry);
          if (zipFileName != null) {
            ZipFile zipFile = new ZipFile(zipFileName);
            ZipEntry zipEntry = findXsdZipEntry(zipFile);
            if (zipEntry != null) {
              xsdInputStream = zipFile.getInputStream(zipEntry);
            }
          }
        }
      } catch (IOException e) {
        // ignore
      }
      /*
      if (xsdInputStream != null) {
        //System.out.println("  found XSD " + xsdInputStream + "...");
        try {
          scanner.scan(xsdInputStream); // adds scan result ComponentSuite to ComponentSuiteRegistry
        } catch (IOException e) {
          Log.e("Error while scanning XSD file " + xsdInputStream, e);
        }
      }
      */
    }
  }

  static String findDependentModuleZipFileName(OrderEntry orderEntry) throws IOException {
    VirtualFile[] files = orderEntry.getFiles(OrderRootType.CLASSES);
    // check that library is not empty:
    for (VirtualFile file : files) {
      // TODO: make it work for classes, not only for jars!
      String filename = file.getPath();
      if (filename.endsWith("!/")) { // it is a jar:
        return filename.substring(0, filename.length() - "!/".length());
      }
    }
    return null;
  }

  static ZipEntry findXsdZipEntry(ZipFile zipFile) throws IOException {
    // find a *.xsd in jar's root folder:
    Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
    while (enumeration.hasMoreElements()) {
      ZipEntry zipEntry = enumeration.nextElement();
      if (!zipEntry.isDirectory() && zipEntry.getName().indexOf('/') == -1 && zipEntry.getName().endsWith(".xsd")) {
        return zipEntry;
      }
    }
    return null;
  }

  @Override
  protected String getOutputFileSuffix() {
    return Jooc.AS_SUFFIX;
  }

  @Override
  protected OutputSinkItem compile(CompileContext context, Module module, List<VirtualFile> files) {
    ExmlcConfigurationBean exmlcConfigurationBean = getExmlConfig(module);
    ExmlConfiguration exmlConfiguration = new ExmlConfiguration();
    updateFileLocations(exmlConfiguration, module, files);
    String generatedSourcesDirectory = exmlcConfigurationBean.getGeneratedSourcesDirectory();
    exmlConfiguration.setOutputDirectory(new File(generatedSourcesDirectory));
    exmlConfiguration.setResourceOutputDirectory(new File(exmlcConfigurationBean.getGeneratedResourcesDirectory()));
    exmlConfiguration.setConfigClassPackage("acme.config"); // TODO: exmlcConfigurationBean.getConfigClassPackage();
    Exmlc exmlc = new Exmlc(exmlConfiguration);
    OutputSinkItem outputSinkItem = null;
    for (final VirtualFile file : files) {
      if (outputSinkItem == null) {
        ModuleFileIndex moduleFileIndex = ModuleRootManager.getInstance(module).getFileIndex();
        if (!moduleFileIndex.isInSourceContent(file) || moduleFileIndex.isInTestSourceContent(file)) {
          // prevent NPE in EXML generator when <file> is not under non-test source root:
          continue;
        }
        outputSinkItem = createGeneratedSourcesOutputSinkItem(context, generatedSourcesDirectory);
        File exmlSourceFile = new File(file.getPath());
        File componentClassOutputFile = exmlc.generateComponentClass(exmlSourceFile);
        File configClassOutputFile = null;
        // TODO: compiler errors!
        if (componentClassOutputFile != null) { 
          configClassOutputFile = exmlc.generateConfigClass(exmlSourceFile);
          if (configClassOutputFile != null) {
            OutputItem outputItem = new OutputItemImpl(componentClassOutputFile.getPath().replace(File.separatorChar, '/'), file);
            if (exmlcConfigurationBean.isShowCompilerInfoMessages()) {
              context.addMessage(CompilerMessageCategory.INFORMATION, "exml->as (" + outputItem.getOutputPath() + ")", file.getUrl(), -1, -1);
            }
            getLog().info("exml->as: " + file.getUrl() + " -> " + outputItem.getOutputPath());
            // TODO: the next commented line raises warning in idea.log. Still needed?
            // LocalFileSystem.getInstance().refreshIoFiles(Arrays.asList(componentClassOutputFile));
            outputSinkItem.addOutputItem(file, componentClassOutputFile);
            outputSinkItem.addOutputItem(file, configClassOutputFile);
          }
        }
        if (configClassOutputFile == null) {
          //context.addMessage(CompilerMessageCategory.INFORMATION, "exml->as compilation failed.", file.getUrl(), -1, -1);
          outputSinkItem.addFileToRecompile(file);
        }
      }
    }
    return outputSinkItem;
  }

  public static String getOrCreateGeneratedAs3RootDir(Module module) {
    ExmlcConfigurationBean exmlConfig = getExmlConfig(module);
    return exmlConfig == null ? null : getVFPath(exmlConfig.getGeneratedSourcesDirectory());
  }

  public static String getVFPath(String path) {
    VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(path);
    return virtualFile == null ? null : virtualFile.getPath();
  }

  static Logger getLog() {
    return Logger.getInstance("ExmlCompiler");
  }

  private static class IdeaErrorHandler implements LogHandler {
    private final CompileContext context;
    private boolean showCompilerInfoMessages = true;
    private File currentFile;

    public IdeaErrorHandler(CompileContext context) {
      this.context = context;
    }

    public void setShowCompilerInfoMessages(boolean showCompilerInfoMessages) {
      this.showCompilerInfoMessages = showCompilerInfoMessages;
    }

    public void setCurrentFile(File file) {
      this.currentFile = file;
    }

    private VirtualFile addMessage(CompilerMessageCategory compilerMessageCategory, String msg, int lineNumber, int columnNumber) {
      VirtualFile file = currentFile == null ? null : LocalFileSystem.getInstance().findFileByPath(currentFile.getAbsolutePath());
      String fileUrl = file == null ? null : file.getUrl();
      context.addMessage(compilerMessageCategory, msg, fileUrl, lineNumber, columnNumber);
      return file;
    }

    public void error(String message, int lineNumber, int columnNumber) {
      addMessage(CompilerMessageCategory.ERROR, message, lineNumber, columnNumber);
      getLog().debug("EXML Compiler Error: " + message);
    }

    public void error(String message, Exception exception) {
      error(message + ": " + exception.getLocalizedMessage(), -1, -1);
    }

    public void error(String message) {
      error(message, -1, -1);
    }

    public void warning(String message) {
      warning(message, -1, -1);
    }

    public void warning(String message, int lineNumber, int columnNumber) {
      addMessage(CompilerMessageCategory.WARNING, message, lineNumber, columnNumber);
      getLog().debug("EXML Compiler Warning: " + message);
    }

    public void info(String message) {
      addMessage(CompilerMessageCategory.INFORMATION, message, -1, -1);
      getLog().debug("EXML Compiler Info: " + message);
    }

    public void debug(String message) {
      getLog().debug(message);
      //ignore debug messages for now
    }
  }

}