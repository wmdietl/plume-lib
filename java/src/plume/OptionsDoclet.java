// The five files
//   Option.java
//   OptionGroup.java
//   Options.java
//   Unpublicized.java
//   OptionsDoclet.java
// together comprise the implementation of command-line processing.

package plume;

import java.io.*;
import java.util.*;
import com.sun.javadoc.*;

import java.lang.Class;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.StringEscapeUtils;

/**
 * Generates HTML documentation of command-line options.
 * <p>
 *
 * <b>Usage</b> <p>
 * This doclet is typically invoked with:
 * <pre>javadoc -quiet -doclet plume.OptionsDoclet [doclet options] [java files]</pre>
 * <p>
 *
 * <b>Doclet Options</b> <p>
 * The following doclet options are supported:
 * <ul>
 * <li> <b>-docfile</b> <i>file</i> When specified, the output of this doclet
 * is the result of replacing everything between the two lines
 * <pre>&lt;!-- start options doc (DO NOT EDIT BY HAND) --&gt;</pre>
 * and
 * <pre>&lt;!-- end options doc --&gt;</pre>
 * in <i>file</i> with the options documentation.  This can be used for
 * inserting option documentation into an existing manual.  The existing
 * docfile is not modified; output goes to the <code>-outfile</code>
 * argument, or to standard out.
 *
 * <li> <b>-outfile</b> <i>file</i> The destination for the output (the default
 * is standard out).  If both <code>-outfile</code> and <code>-docfile</code>
 * are specified, they must be different.
 *
 * <li> <b>-i</b> Specifies that the docfile should be edited in-place.  This
 * option can not be used at the same time as the <code>-outfile</code> option.
 *
 * <li> <b>-format</b> <i>format</i> This option sets the output format of this
 * doclet.  Currently, the following value(s) for <i>format</i> are supported:
 * <ul>
 *   <li> <b>javadoc</b> When this format is specified, the output of this
 *   doclet is formatted as a Javadoc comment.  This is useful for including
 *   option documentation inside Java source code.  When this format is used
 *   with the <code>-docfile</code> option, the generated documentation is
 *   inserted between the lines
 *   <pre>* &lt;!-- start options doc (DO NOT EDIT BY HAND) --&gt;</pre>
 *   and
 *   <pre>* &lt;!-- end options doc --&gt;</pre>
 *   using the same indentation.  For the most part, the output with this format
 *   is the same as the default HTML output with the string "* " prepended to
 *   every line.
 * </ul>
 * The default output format is HTML; this is the format used when
 * <code>-format</code> is not specified.
 *
 * <li> <b>-classdoc</b> When specified, the output of this doclet includes the
 * class documentation of the first class specified on the command-line.
 *
 * <li> <b>-singledash</b> Use single dashes for long options.  See {@link
 * plume.Options#use_single_dash(boolean)}.
 * </ul>
 * <p>
 *
 * <b>Examples</b> <p>
 * To update the Javarifier HTML manual with option documentation run:
 * <pre>javadoc -quiet -doclet plume.OptionsDoclet -i -docfile javarifier.html src/javarifier/Main.java</pre>
 * <p>
 *
 * To update the class Javadoc for plume.Lookup with option documentation run:
 * <pre>javadoc -quiet -doclet plume.OptionsDoclet -i -docfile Lookup.java -format javadoc Lookup.java</pre>
 * <p>
 *
 * <b>Caveats</b> <p>
 * The generated HTML documentation includes unpublicized option groups but not
 * <code>@Unpublicized</code> options.  Option groups which contain only
 * <code>@Unpublicized</code> options are not included in the output at all.
 *
 * @see plume.Option
 * @see plume.Options
 * @see plume.OptionGroup
 * @see plume.Unpublicized
 */

// This doesn't itself use plume.Options for its command-line option
// processing because a Doclet is required to implement the optionLength
// and validOptions methods.
public class OptionsDoclet {

  @SuppressWarnings("nullness") // line.separator property always exists
  private static String eol = System.getProperty("line.separator");

  private static String usage = "Provided by Options doclet:\n" +
    "-docfile <file>        Specify file into which options documentation is inserted\n" +
    "-outfile <file>        Specify destination for resulting output\n" +
    "-i                     Edit the docfile in-place\n" +
    "-format javadoc        Format output as a Javadoc comment\n" +
    "-classdoc              Include 'main' class documentation in output\n" +
    "-singledash            Use single dashes for long options (see plume.Options)\n" +
    "See the OptionsDoclet documentation for more details.";


  private String startDelim = "<!-- start options doc (DO NOT EDIT BY HAND) -->";
  private String endDelim = "<!-- end options doc -->"; 

  private File docFile;
  private File outFile;
  private boolean inPlace = false;
  private boolean formatJavadoc = false;
  private boolean includeClassDoc = false;

  private RootDoc root;
  private Options options;

  public OptionsDoclet(RootDoc root, Options options) {
    this.root = root;
    this.options = options;
  }

  // Doclet-specific methods

  /**
   * Entry point for the doclet.
   */
  public static boolean start(RootDoc root) {
    List<Class<?>> classes = new ArrayList<Class<?>>();
    for (ClassDoc doc : root.specifiedClasses()) {
      // TODO: Class.forName() expects a binary name but doc.qualifiedName()
      // returns a fully qualified name.  I do not know a good way to convert
      // between these two name formats.  For now, we simply ignore inner
      // classes.  This limitation can be removed when we figure out a better
      // way to go from ClassDoc to Class<?>.
      if (doc.containingClass() != null)
        continue;
      try {
        classes.add(Class.forName(doc.qualifiedName(), true, Thread.currentThread().getContextClassLoader()));
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
        return false;
      }
    }

    Object[] classarr = classes.toArray();
    Options options = new Options(classarr);
    if (options.getOptions().size() < 1) {
      System.out.println("Error: no @Option-annotated fields found");
      return false;
    }

    OptionsDoclet o = new OptionsDoclet(root, options);
    o.setOptions(root.options());
    o.processJavadoc();
    try {
      o.write();
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }

    return true;
  }

  /**
   * Returns the number of tokens corresponding to a command-line argument of
   * this doclet or 0 if the argument is unrecognized.  This method is
   * automatically invoked.
   *
   * @see <a href="http://java.sun.com/javase/6/docs/technotes/guides/javadoc/doclet/overview.html">Doclet overview</a>
   */
  public static int optionLength(String option) {
    if (option.equals("-help")) {
      System.out.println(usage);
      return 1;
    }
    if (option.equals("-i") ||
        option.equals("-classdoc") ||
        option.equals("-singledash")) {
      return 1;
    }
    if (option.equals("-docfile") ||
        option.equals("-outfile") ||
        option.equals("-format")) {
      return 2;
    }
    return 0;
  }

  /**
   * Tests the validity of command-line arguments passed to this doclet.
   * Returns true if the option usage is valid, and false otherwise.  This
   * method is automatically invoked.
   *
   * @see <a href="http://java.sun.com/javase/6/docs/technotes/guides/javadoc/doclet/overview.html">Doclet overview</a>
   */
  public static boolean validOptions(String options[][],
                                     DocErrorReporter reporter) {
    boolean hasDocFile = false;
    boolean hasOutFile = false;
    boolean hasFormat = false;
    boolean inPlace = false;
    String docFile = null;
    String outFile = null;
    for (int oi = 0; oi < options.length; oi++) {
      String[] os = options[oi];
      String opt = os[0].toLowerCase();
      if (opt.equals("-docfile")) {
        if (hasDocFile) {
          reporter.printError("-docfile option specified twice");
          return false;
        }
        File f = new File(os[1]);
        if (!f.exists()) {
          reporter.printError("file not found: " + os[1]);
          return false;
        }
        docFile = os[1];
        hasDocFile = true;
      }
      if (opt.equals("-outfile")) {
        if (hasOutFile) {
          reporter.printError("-outfile option specified twice");
          return false;
        }
        if (inPlace) {
          reporter.printError("-i and -outfile can not be used at the same time");
          return false;
        }
        outFile = os[1];
        hasOutFile = true;
      }
      if (opt.equals("-i")) {
        if (hasOutFile) {
          reporter.printError("-i and -outfile can not be used at the same time");
          return false;
        }
        inPlace = true;
      }
      if (opt.equals("-format")) {
        if (hasFormat) {
          reporter.printError("-format option specified twice");
          return false;
        }
        if (!os[1].equals("javadoc")) {
          reporter.printError("unrecognized output format: " + os[1]);
          return false;
        }
        hasFormat = true;
      }
    }
    if (docFile != null && outFile != null && outFile.equals(docFile)) {
      reporter.printError("docfile must be different from outfile");
      return false;
    }
    return true;
  }

  /**
   * Set the options for this class based on command-line arguments given by
   * RootDoc.options().
   */
  public void setOptions(String[][] options) {
    for (int oi = 0; oi < options.length; oi++) {
      String[] os = options[oi];
      String opt = os[0].toLowerCase();
      if (opt.equals("-docfile")) {
        this.docFile = new File(os[1]);
      } else if (opt.equals("-outfile")) {
        this.outFile = new File(os[1]);
      } else if (opt.equals("-i")) {
        this.inPlace = true;
      } else if (opt.equals("-format")) {
        if (os[1].equals("javadoc"))
          setFormatJavadoc(true);
      } else if (opt.equals("-classdoc")) {
        this.includeClassDoc = true;
      } else if (opt.equals("-singledash")) {
          setUseSingleDash(true);
      }
    }
  }

  // File IO methods

  /**
   * Write the output of this doclet to the correct file.
   */
  public void write() throws Exception {
    PrintWriter out;
    String output = output();

    if (outFile != null)
      out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
    else if (inPlace)
      out = new PrintWriter(new BufferedWriter(new FileWriter(docFile)));
    else
      out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));

    out.println(output);
    out.flush();
    out.close();
  }

  /**
   * Get the final output of this doclet.  The string returned by this method
   * is the output seen by the user.
   */
  public String output() throws Exception {
    if (docFile == null) {
      if (formatJavadoc)
        return optionsToJavadoc(0);
      else
        return optionsToHtml();
    }

    return newDocFileText();
  }

  /**
   * Get the result of inserting the options documentation into the docfile.
   */
  private String newDocFileText() throws Exception {
    StringBuilderDelimited b = new StringBuilderDelimited(eol);
    BufferedReader doc = new BufferedReader(new FileReader(docFile));
    String docline;
    boolean replacing = false;
    boolean replaced_once = false;

    while ((docline = doc.readLine()) != null) {
      if (replacing) {
        if (docline.trim().equals(endDelim))
          replacing = false;
        else
          continue;
      }

      b.append(docline);

      if (!replaced_once && docline.trim().equals(startDelim)) {
        if (formatJavadoc)
          b.append(optionsToJavadoc(docline.indexOf('*')));
        else
          b.append(optionsToHtml());
        replaced_once = true;
        replacing = true;
      }
    }

    doc.close();
    return b.toString();
  }

  // HTML and Javadoc processing methods

  /**
   * Process each option and add in the Javadoc info.
   */
  public void processJavadoc() {
    for (Options.OptionInfo oi : options.getOptions()) {
      ClassDoc opt_doc = root.classNamed(oi.get_declaring_class().getName());
      if (opt_doc != null) {
        String nameWithUnderscores = oi.long_name.replace('-', '_');
        for (FieldDoc fd : opt_doc.fields()) {
          if (fd.name().equals (nameWithUnderscores)) {
            // If Javadoc for field is unavailable, then use the @Option
            // description in the documentation.
            if (fd.getRawCommentText().length() == 0) {
              // Input is a string rather than a Javadoc (HTML) comment so we
              // must escape it.
              oi.jdoc = StringEscapeUtils.escapeHtml(oi.description);
            } else if (formatJavadoc) {
              oi.jdoc = fd.commentText();
            } else {
              oi.jdoc = javadocToHtml(fd);
            }
            break;
          }
        }
      }
    }
  }

  /**
   * Get the HTML documentation for the underlying options instance.
   */
  public String optionsToHtml() {
    StringBuilderDelimited b = new StringBuilderDelimited(eol);

    if (includeClassDoc) {
      b.append(OptionsDoclet.javadocToHtml(root.classes()[0]));
      b.append("<p>Command line options: </p>");
    }

    b.append("<ul>");
    if (!options.isUsingGroups()) {
      b.append(optionListToHtml(options.getOptions(), 2));
    } else {
      for (Options.OptionGroupInfo gi : options.getOptionGroups()) {
        // Do not include groups without publicized options in output
        if (!gi.containsPublicizedOption())
          continue;

        b.append("  <li>" + gi.name);
        b.append("    <ul>");
        b.append(optionListToHtml(gi.optionList, 6));
        b.append("    </ul>");
        b.append("  </li>");
      }
    }
    b.append("</ul>");

    return b.toString();
  }

  /**
   * Get the HTML documentation for the underlying options instance, formatted
   * as a Javadoc comment.
   */
  public String optionsToJavadoc(int padding) {
    StringBuilderDelimited b = new StringBuilderDelimited(eol);
    Scanner s = new Scanner(optionsToHtml());

    while (s.hasNextLine()) {
      StringBuilder bb = new StringBuilder();
      bb.append(StringUtils.repeat(" ", padding)).append("* ").append(s.nextLine());
      b.append(bb);
    }

    return b.toString();
  }

  /**
   * Get the HTML describing many options, formatted as an HTML list.
   */
  private String optionListToHtml(List<Options.OptionInfo> opt_list, int padding) {
    StringBuilderDelimited b = new StringBuilderDelimited(eol);
    for (Options.OptionInfo oi : opt_list) {
      if (oi.unpublicized)
        continue;
      StringBuilder bb = new StringBuilder();
      String optHtml = optionToHtml(oi);
      bb.append(StringUtils.repeat(" ", padding));
      bb.append("<li>").append(optHtml).append("</li>");
      b.append(bb);
    }
    return b.toString();
  }

  /**
   * Get the line of HTML describing an Option.
   */
  public String optionToHtml(Options.OptionInfo oi) {
    StringBuilder b = new StringBuilder();
    Formatter f = new Formatter(b);
    if (oi.short_name != null)
      f.format("<b>-%s</b> ", oi.short_name);
    for (String a : oi.aliases)
      f.format("<b>%s</b> ", a);
    String prefix = getUseSingleDash() ? "-" : "--";
    f.format("<b>%s%s=</b><i>%s</i>. ", prefix, oi.long_name, oi.type_name);
    String default_str = "no default";
    if (oi.default_str != null)
      default_str = "default " + oi.default_str;
    String jdoc = oi.jdoc == null ? "" : oi.jdoc; // FIXME: suppress nullness warnings
    // The default string must be HTML escaped since it comes from a string
    // rather than a Javadoc comment.
    f.format("%s [%s]", jdoc, StringEscapeUtils.escapeHtml(default_str));
    return b.toString();
  }

  /**
   * Replace the @link tags and block @see tags in a Javadoc comment with
   * sensible, non-hyperlinked HTML.  This keeps most of the information in the
   * comment while still being presentable. <p>
   * 
   * This is only a temporary solution.  Ideally, @link/@see tags would be
   * converted to HTML links which point to actual documentation.
   */
  public static String javadocToHtml(Doc doc) {
    StringBuilder b = new StringBuilder();
    Tag[] tags = doc.inlineTags();
    for (Tag tag : tags) {
      if (tag instanceof SeeTag)
        b.append("<code>" + tag.text() + "</code>");
      else
        b.append(tag.text());
    }
    SeeTag[] seetags = doc.seeTags();
    if (seetags.length > 0) {
      b.append(" See: ");
      StringBuilderDelimited bb = new StringBuilderDelimited(", ");
      for (SeeTag tag : seetags)
        bb.append("<code>" + tag.text() + "</code>");
      b.append(bb);
      b.append(".");
    }
    return b.toString();
  }

  // Getters and Setters

  public boolean getFormatJavadoc() {
    return formatJavadoc;
  }

  public void setFormatJavadoc(boolean val) {
    if (val && !formatJavadoc) {
      startDelim = "* " + startDelim;
      endDelim = "* " + endDelim;
    } else if (!val && formatJavadoc) {
      startDelim = StringUtils.removeStart("* ", startDelim);
      endDelim = StringUtils.removeStart("* ", endDelim);
    }
    this.formatJavadoc = val;
  }

  public boolean getUseSingleDash() {
    return options.isUsingSingleDash();
  }

  public void setUseSingleDash(boolean val) {
    options.use_single_dash(true);
  }
}
