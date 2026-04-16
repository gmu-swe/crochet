package net.jonbell.crij.instrument;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

public class Instrumenter {
    public static ClassLoader loader;

    static String curPath;
    public static boolean isIgnoredClass(Class<?> c) {
    	if(c.ignored == 0)
    	{
    		boolean ret = isIgnoredClass(c.getName().replace('.', '/'));
			if (ret)
				c.ignored = 1;
			else
				c.ignored = -1;
    	}
    	return c.ignored == 1;
    }
    
    public static boolean isIgnoredFromClassCoverage(String owner)
    {
    	return owner.startsWith("java/lang/Class") || isIgnoredClassWithDummyMethods(owner) || owner.startsWith("sun/misc/VM")
    			 || isIgnoredClass(owner) || owner.startsWith("java/util/WeakHashMap");
    }

	public static boolean isIgnoredClassButStillPropogate(String owner) {
		return owner.startsWith("org/junit/") || owner.startsWith("junit/") || owner.startsWith("net/jonbell/crij/test/ignored")
				||owner.equals("java/lang/Class$ReflectionData")
				|| owner.equals("java/lang/ref/ReferenceQueue") 
				|| owner.equals("java/lang/Enum")
				|| owner.equals("java/lang/invoke/BoundMethodHandle")
				|| owner.equals("java/lang/invoke/DirectMethodHandle")
				|| owner.equals("java/lang/invoke/MethodHandle")
				//|| owner.startsWith("java/lang/reflect/Method")

;
	}
	public static boolean isIgnoredClassWithDummyMethods(Class<?> c) {
		String owner = c.getName().replace('.', '/');
		return isIgnoredClassWithDummyMethods(owner);
	}
	public static boolean isIgnoredClassWithDummyMethods(String owner) {
		return owner.equals("java/lang/Class") ||
				owner.equals("java/lang/reflect/Method") ||
				owner.equals("java/lang/reflect/Constructor") ||
				owner.equals("java/lang/reflect/Field") ||
				owner.startsWith("sun/nio/cs/UTF_8") ||
				owner.equals("sun/misc/Launcher$AppClassLoader") ||
//				owner.equals("java/lang/ref/Finalizer$FinalizerThread") ||
				owner.equals("org/apache/log4j/Logger") ||
				owner.startsWith("java/util/regex/Pattern") ||
				owner.equals("org/mockito/internal/creation/jmock/SerializableNoOp") ||
				owner.startsWith("java/lang/reflect/Proxy") ||
				owner.startsWith("java/util/Locale") ||
				owner.equals("java/net/URL") ||
				owner.startsWith("java/util/concurrent/locks") ||
				owner.equals("sun/misc/Unsafe") ||
				false
				;
	}
	public static boolean isIgnoredClass(String owner) {
		return owner.startsWith("java/lang/Object") || owner.startsWith("java/lang/Double") || owner.startsWith("java/lang/Long") || owner.startsWith("java/lang/Number")           || owner.startsWith("java/lang/Comparable") 
				|| owner.startsWith("java/lang/ref/FinalizerReference") || owner.startsWith("java/lang/Boolean") || owner.startsWith("java/lang/Character") || owner.startsWith("java/lang/Float")
				|| owner.startsWith("java/lang/Byte") || owner.startsWith("java/lang/Short") || owner.startsWith("java/lang/Integer") || owner.startsWith("java/lang/StackTraceElement")
				|| owner.startsWith("sun/awt/image/codec/") || (owner.startsWith("sun/reflect/Reflection")) || owner.equals("java/lang/reflect/Proxy")
				|| owner.equals("java/io/Serializable")
				|| owner.startsWith("sun/reflection/annotation/AnnotationParser") || owner.startsWith("sun/reflect/MethodAccessor") || owner.startsWith("sun/reflect/ConstructorAccessor")
				|| owner.startsWith("sun/reflect/SerializationConstructorAccessor") || owner.startsWith("sun/reflect/GeneratedMethodAccessor")
				|| owner.startsWith("sun/reflect/GeneratedConstructorAccessor") || owner.startsWith("sun/reflect/GeneratedSerializationConstructor")
				//|| owner.startsWith("java/lang/reflect/Field"))
				|| owner.startsWith("sun/reflect/Unsafe") || 
				owner.startsWith("sun/misc/Unsafe")
				|| 	owner.startsWith("sun/misc/Java")
				//				|| Configuration.taintTagFactory.isIgnoredClass(owner)
				|| owner.startsWith("edu/columbia/cs/psl/testdepends") || owner.startsWith("sun/instrument/") || owner.startsWith("edu/columbia/cs/psl/phosphor") || owner.startsWith("de/unisb/cs")
				|| owner.startsWith("de/unisb") || owner.startsWith("de/hammacher")
				|| owner.contains("package-info")
				//                || owner.startsWith("jdk/internal")
                || owner.startsWith("java/lang/invoke/InvokerBytecodeGenerator")
                || owner.equals("java/lang/ref/Finalizer")
//				|| owner.equals("java/lang/invoke/MethodHandle")
//								|| owner.startsWith("java/lang/invoke/Volatile")
//								|| owner.startsWith("java/lang/invoke/CallSite")
//				|| owner.startsWith("java/lang/invoke/BoundMethodHandle")
//								|| owner.startsWith("java/lang/invoke/DirectMethodHandle")
				|| owner.startsWith("java/lang/invoke/LambdaForm")
//				|| owner.startsWith("java/lang/invoke/LambdaForm$DMH") //CAN NOT change constant pool on this - breaks anonymous class constant pool patching
//				|| owner.startsWith("java/lang/invoke/LambdaForm$MH") //CAN NOT change constant pool on this - breaks anonymous class constant pool patching
				|| owner.startsWith("java/lang/invoke/BoundMethodHandle$Species_") //CAN NOT change constant pool on this - breaks anonymous class constant pool patching
				                || owner.startsWith("net/jonbell/crij/runtime/")
				                || owner.startsWith("net/jonbell/crij/instrument/")
				                || owner.startsWith("net/jonbell/crij/org")
				                || owner.startsWith("com/sun/proxy")
				                || owner.startsWith("com/jprofiler")
				                || owner.startsWith("java/lang/annotation/Annotation")
				                || owner.equals("java/lang/String")
//				                || owner.equals("java/security/ProtectionDomain")
				                || owner.equals("sun/reflect/FieldAccessorImpl")
				                || owner.equals("sun/reflect/FieldAccessor")
				                || owner.startsWith("sun/reflect/MagicAccessor")
				                || owner.startsWith("java/lang/ref/FinalReference")
				//                || owner.equals("java/lang/Throwable")
				//                || owner.equals("java/lang/System");
				|| owner.startsWith("java/lang/ref/SoftReference") || owner.equals("java/lang/ref/Reference")
				|| owner.startsWith("java/lang/ref/WeakReference")
				|| owner.contains("$Lambda")
				|| owner.equals("org/jruby/ext/posix/LibC")
				|| owner.startsWith("net/jonbell/dacapo")
				;
	}

    static int n = 0;

    public static byte[] instrumentClass(String path, InputStream is, boolean renameInterfaces) {
        try {
            n++;
            if (n % 1000 == 0)
                System.out.println("Processed: " + n);
            curPath = path;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            int nRead;
            byte[] data = new byte[16384];

            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            buffer.flush();
            CRClassFileTransformer transformer = new CRClassFileTransformer();
            byte[] ret = transformer.transform(Instrumenter.loader, path, null, null, buffer.toByteArray());
            curPath = null;
            return ret;
        } catch (Exception ex) {
            curPath = null;
            ex.printStackTrace();
            return null;
        }
    }

    static Option help = new Option("help", "print this message");

    public static void main(String[] args) {

        Options options = new Options();
        options.addOption(help);

        CommandLineParser parser = new BasicParser();
        CommandLine line = null;
        try {
            line = parser.parse(options, args);
        } catch (org.apache.commons.cli.ParseException exp) {

            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("java -jar testdepends.jar [OPTIONS] [input] [output]", options);
            System.err.println(exp.getMessage());
            return;
        }
        if (line.hasOption("help") || line.getArgs().length != 2) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("java -jar testdepends.jar [OPTIONS] [input] [output]", options);
            return;
        }

        _main(line.getArgs());
        System.out.println("Done");
    }

    static File rootOutputDir;

    public static void _main(String[] args) {

        String outputFolder = args[1];
        rootOutputDir = new File(outputFolder);
        if (!rootOutputDir.exists())
            rootOutputDir.mkdir();
        String inputFolder = args[0];
        // Setup the class loader
        final ArrayList<URL> urls = new ArrayList<URL>();
        Path input = FileSystems.getDefault().getPath(args[0]);
        try {
            if (Files.isDirectory(input))
                Files.walkFileTree(input, new FileVisitor<Path>() {
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".jar"))
                            urls.add(file.toUri().toURL());
                        return FileVisitResult.CONTINUE;
                    }

                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }
                });
            else if (inputFolder.endsWith(".jar"))
                urls.add(new File(inputFolder).toURI().toURL());
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        try {
            urls.add(new File(inputFolder).toURI().toURL());
        } catch (MalformedURLException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        File f = new File(inputFolder);
        if (!f.exists()) {
            System.err.println("Unable to read path " + inputFolder);
            System.exit(-1);
        }
        if (f.isDirectory())
            processDirectory(f, rootOutputDir, true);
        else if (inputFolder.endsWith(".jar") || inputFolder.endsWith(".war"))
            processJar(f, rootOutputDir);
        else if (inputFolder.endsWith(".class"))
            try {
                processClass(f.getName(), new FileInputStream(f), rootOutputDir);
            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        else if (inputFolder.endsWith(".zip")) {
            processZip(f, rootOutputDir);
        } else {
            System.err.println("Unknown type for path " + inputFolder);
            System.exit(-1);
        }
    }

    static String lastInstrumentedClass;

    private static void processClass(String name, InputStream is, File outputDir) {

        try {
            FileOutputStream fos = new FileOutputStream(outputDir.getPath() + File.separator + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            lastInstrumentedClass = outputDir.getPath() + File.separator + name;

            byte[] c = instrumentClass(outputDir.getAbsolutePath(), is, true);
            bos.write(c);
            bos.writeTo(fos);
            fos.close();
            is.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void processDirectory(File f, File parentOutputDir, boolean isFirstLevel) {
        if (f.getName().equals(".AppleDouble"))
            return;
        File thisOutputDir;
        if (isFirstLevel) {
            thisOutputDir = parentOutputDir;
        } else {
            thisOutputDir = new File(parentOutputDir.getAbsolutePath() + File.separator + f.getName());
            thisOutputDir.mkdir();
        }
        for (File fi : f.listFiles()) {
            if (fi.isDirectory())
                processDirectory(fi, thisOutputDir, false);
            else if (fi.getName().endsWith(".class"))
                try {
                    processClass(fi.getName(), new FileInputStream(fi), thisOutputDir);
                } catch (FileNotFoundException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            else if (fi.getName().endsWith(".jar") || fi.getName().endsWith(".war"))
                //				try {
                //					FileOutputStream fos = new FileOutputStream(thisOutputDir.getPath() + File.separator + f.getName());
                processJar(fi, thisOutputDir);
            //					fos.close();
            //				} catch (IOException e1) {
            // TODO Auto-generated catch block
            //					e1.printStackTrace();
            //				}
            else if (fi.getName().endsWith(".zip"))
                processZip(fi, thisOutputDir);
            else {
                File dest = new File(thisOutputDir.getPath() + File.separator + fi.getName());
                FileChannel source = null;
                FileChannel destination = null;

                try {
                    source = new FileInputStream(fi).getChannel();
                    destination = new FileOutputStream(dest).getChannel();
                    destination.transferFrom(source, 0, source.size());
                } catch (Exception ex) {
                    System.err.println("error copying file " + fi);
                    ex.printStackTrace();
                    //					logger.log(Level.SEVERE, "Unable to copy file " + fi, ex);
                    //					System.exit(-1);
                } finally {
                    if (source != null) {
                        try {
                            source.close();
                        } catch (IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                    if (destination != null) {
                        try {
                            destination.close();
                        } catch (IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }

            }
        }

    }

    public static void processJar(File f, File outputDir) {
        try {
            //			@SuppressWarnings("resource")
            //			System.out.println("File: " + f.getName());
            JarFile jar = new JarFile(f);
            JarOutputStream jos = null;
            jos = new JarOutputStream(new FileOutputStream(outputDir.getPath() + File.separator + f.getName()));
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().endsWith(".class")) {
                    {

                        try {
                            JarEntry outEntry = new JarEntry(e.getName());
                            jos.putNextEntry(outEntry);
                            byte[] clazz = instrumentClass(f.getAbsolutePath(), jar.getInputStream(e), true);
                            if (clazz == null) {
                                System.out.println("Failed to instrument " + e.getName() + " in " + f.getName());
                                InputStream is = jar.getInputStream(e);
                                byte[] buffer = new byte[1024];
                                while (true) {
                                    int count = is.read(buffer);
                                    if (count == -1)
                                        break;
                                    jos.write(buffer, 0, count);
                                }
                            } else {
                                jos.write(clazz);
                            }
                            jos.closeEntry();
                        } catch (ZipException ex) {
                            ex.printStackTrace();
                            continue;
                        }

                    }

                } else {
                    JarEntry outEntry = new JarEntry(e.getName());
                    if (e.isDirectory()) {
                        try {
                            jos.putNextEntry(outEntry);
                            jos.closeEntry();
                        } catch (ZipException exxx) {
                            System.out.println("Ignoring exception: " + exxx);
                        }
                    } else if (e.getName().startsWith("META-INF") && (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA"))) {
                        // don't copy this
                    } else if (e.getName().equals("META-INF/MANIFEST.MF")) {
                        Scanner s = new Scanner(jar.getInputStream(e));
                        jos.putNextEntry(outEntry);

                        String curPair = "";
                        while (s.hasNextLine()) {
                            String line = s.nextLine();
                            if (line.equals("")) {
                                curPair += "\n";
                                if (!curPair.contains("SHA1-Digest:"))
                                    jos.write(curPair.getBytes());
                                curPair = "";
                            } else {
                                curPair += line + "\n";
                            }
                        }
                        s.close();
                        //						jos.write("\n".getBytes());
                        jos.closeEntry();
                    } else {
                        try {
                            jos.putNextEntry(outEntry);
                            InputStream is = jar.getInputStream(e);
                            byte[] buffer = new byte[1024];
                            while (true) {
                                int count = is.read(buffer);
                                if (count == -1)
                                    break;
                                jos.write(buffer, 0, count);
                            }
                            jos.closeEntry();
                        } catch (ZipException ex) {
                            if (!ex.getMessage().contains("duplicate entry")) {
                                ex.printStackTrace();
                                System.out.println("Ignoring above warning from improper source zip...");
                            }
                        }
                    }

                }

            }
            if (jos != null) {
                jos.close();

            }
            jar.close();
        } catch (Exception e) {
            System.err.println("Unable to process jar: " + f.getAbsolutePath());
            e.printStackTrace();
            //			logger.log(Level.SEVERE, "Unable to process jar: " + f.getAbsolutePath(), e);
            File dest = new File(outputDir.getPath() + File.separator + f.getName());
            FileChannel source = null;
            FileChannel destination = null;

            try {
                source = new FileInputStream(f).getChannel();
                destination = new FileOutputStream(dest).getChannel();
                destination.transferFrom(source, 0, source.size());
            } catch (Exception ex) {
                System.err.println("Unable to copy file: " + f.getAbsolutePath());
                ex.printStackTrace();
                //				System.exit(-1);
            } finally {
                if (source != null) {
                    try {
                        source.close();
                    } catch (IOException e2) {
                        // TODO Auto-generated catch block
                        e2.printStackTrace();
                    }
                }
                if (destination != null) {
                    try {
                        destination.close();
                    } catch (IOException e2) {
                        // TODO Auto-generated catch block
                        e2.printStackTrace();
                    }
                }
            }
            //			System.exit(-1);
        }

    }

    private static void processZip(File f, File outputDir) {
        try {
            //			@SuppressWarnings("resource")
            ZipFile zip = new ZipFile(f);
            ZipOutputStream zos = null;
            zos = new ZipOutputStream(new FileOutputStream(outputDir.getPath() + File.separator + f.getName()));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();

                if (e.getName().endsWith(".class")) {
                    {
                        ZipEntry outEntry = new ZipEntry(e.getName());
                        zos.putNextEntry(outEntry);

                        byte[] clazz = instrumentClass(f.getAbsolutePath(), zip.getInputStream(e), true);
                        if (clazz == null) {
                            InputStream is = zip.getInputStream(e);
                            byte[] buffer = new byte[1024];
                            while (true) {
                                int count = is.read(buffer);
                                if (count == -1)
                                    break;
                                zos.write(buffer, 0, count);
                            }
                        } else
                            zos.write(clazz);
                        zos.closeEntry();

                    }

                } else if (e.getName().endsWith(".jar")) {
                    ZipEntry outEntry = new ZipEntry(e.getName());
                    //						jos.putNextEntry(outEntry);
                    //						try {
                    //							processJar(jar.getInputStream(e), jos);
                    //							jos.closeEntry();
                    //						} catch (FileNotFoundException e1) {
                    //							// TODO Auto-generated catch block
                    //							e1.printStackTrace();
                    //						}

                    File tmp = new File("/tmp/classfile");
                    if (tmp.exists())
                        tmp.delete();
                    FileOutputStream fos = new FileOutputStream(tmp);
                    byte buf[] = new byte[1024];
                    int len;
                    InputStream is = zip.getInputStream(e);
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                    is.close();
                    fos.close();
                    //						System.out.println("Done reading");
                    File tmp2 = new File("tmp2");
                    if (!tmp2.exists())
                        tmp2.mkdir();
                    processJar(tmp, new File("tmp2"));

                    zos.putNextEntry(outEntry);
                    is = new FileInputStream("tmp2/classfile");
                    byte[] buffer = new byte[1024];
                    while (true) {
                        int count = is.read(buffer);
                        if (count == -1)
                            break;
                        zos.write(buffer, 0, count);
                    }
                    is.close();
                    zos.closeEntry();
                    //						jos.closeEntry();
                } else {
                    ZipEntry outEntry = new ZipEntry(e.getName());
                    if (e.isDirectory()) {
                        try {
                            zos.putNextEntry(outEntry);
                            zos.closeEntry();
                        } catch (ZipException exxxx) {
                            System.out.println("Ignoring exception: " + exxxx.getMessage());
                        }
                    } else if (e.getName().startsWith("META-INF") && (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA"))) {
                        // don't copy this
                    } else if (e.getName().equals("META-INF/MANIFEST.MF")) {
                        Scanner s = new Scanner(zip.getInputStream(e));
                        zos.putNextEntry(outEntry);

                        String curPair = "";
                        while (s.hasNextLine()) {
                            String line = s.nextLine();
                            if (line.equals("")) {
                                curPair += "\n";
                                if (!curPair.contains("SHA1-Digest:"))
                                    zos.write(curPair.getBytes());
                                curPair = "";
                            } else {
                                curPair += line + "\n";
                            }
                        }
                        s.close();
                        zos.write("\n".getBytes());
                        zos.closeEntry();
                    } else {
                        zos.putNextEntry(outEntry);
                        InputStream is = zip.getInputStream(e);
                        byte[] buffer = new byte[1024];
                        while (true) {
                            int count = is.read(buffer);
                            if (count == -1)
                                break;
                            zos.write(buffer, 0, count);
                        }
                        zos.closeEntry();
                    }
                }

            }
            zos.close();
            zip.close();
        } catch (Exception e) {
            System.err.println("Unable to process zip: " + f.getAbsolutePath());
            e.printStackTrace();
            File dest = new File(outputDir.getPath() + File.separator + f.getName());
            FileChannel source = null;
            FileChannel destination = null;

            try {
                source = new FileInputStream(f).getChannel();
                destination = new FileOutputStream(dest).getChannel();
                destination.transferFrom(source, 0, source.size());
            } catch (Exception ex) {
                System.err.println("Unable to copy zip: " + f.getAbsolutePath());
                ex.printStackTrace();
                //				System.exit(-1);
            } finally {
                if (source != null) {
                    try {
                        source.close();
                    } catch (IOException e2) {
                        // TODO Auto-generated catch block
                        e2.printStackTrace();
                    }
                }
                if (destination != null) {
                    try {
                        destination.close();
                    } catch (IOException e2) {
                        // TODO Auto-generated catch block
                        e2.printStackTrace();
                    }
                }
            }
        }

    }

}