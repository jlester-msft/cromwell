import io.github.swagger2markup.builder.Swagger2MarkupConfigBuilder
import io.github.swagger2markup.{MarkupLanguage, Swagger2MarkupConverter}
import org.apache.commons.lang3.{ClassUtils, StringUtils}
import sbt.Keys._
import sbt._

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
  * Provides a task to generate the REST API markdown from the Swagger YAML.
  *
  * Uses imports provided by swagger2markup.sbt.
  *
  * Based partially on https://github.com/21re/sbt-swagger-plugin
  */
object GenerateRestApiDocs {
  private lazy val generateRestApiDocs = taskKey[Unit]("Generates the docs/api/RESTAPI.md")
  private lazy val checkRestApiDocs =
    taskKey[Unit]("Compares the existing docs/api/RESTAPI.md against the generated version")

  /** Returns a timestamped preamble for the modified markdown output. */
  private def preamble(): String = {
    val now = OffsetDateTime.now()
    s"""|<!--
        |This file was generated by `sbt generateRestApiDocs` on ${now.format(DateTimeFormatter.RFC_1123_DATE_TIME)}
        |
        |!!! DO NOT CHANGE THIS FILE DIRECTLY !!!
        |
        |If you wish to change something in this file, either change cromwell.yaml or GenerateRestApiDocs.scala then
        |regenerate.
        |-->
        |""".stripMargin
  }

  // Path to the input swagger yaml
  private val SwaggerYamlFile = new File("engine/src/main/resources/swagger/cromwell.yaml")

  // Path to the generated markdown
  private val RestApiMarkdownFile = new File("docs/api/RESTAPI.md")

  // A regex to locate the collection of swagger paths.
  private val PathsRegex = "(?s)(.*## Paths)(.*)(## Definitions.*)".r
  // A regex to locate an individual swagger path within the above collection. Each is turned into "## <original text>"
  private val PathRegex = "(?s)### (.*)".r

  private val GenericReplacements = List(
    // Change a few section headers into bolded text.
    "## Overview" -> "**Overview**  ",
    "### Version information" -> "**Version information**  ",
    "### License information" -> "**License information**  ",
    "### Produces" -> "**Produces**  ",
    // Since individual paths are moved from "###" to "##" we don't need "Paths" anymore.
    "<a name=\"paths\"></a>" -> "",
    "## Paths" -> ""
  )

  /**
    * Move contents of the "## Paths" section up a level.
    *
    * Each "### example path" will be come "## example path".
    *
    * Should be run before `replaceGenerics` as it looks for the string "## Paths".
    *
    * @param content The original contents of the RESTAPI.md.
    * @return The contents with updated paths.
    */
  private def replacePaths(content: String): String =
    content match {
      case PathsRegex(start, paths, end) =>
        val replacedPaths = paths.linesWithSeparators map {
          case PathRegex(pathDescription) => s"## $pathDescription"
          case other => other
        }
        replacedPaths.mkString(start, "", end)
      case _ =>
        throw new IllegalArgumentException(
          "Content did not match expected regex. " +
            "Did the swagger2markdown format change significantly? " +
            "If so, a new regex may be required."
        )
    }

  /**
    * Replaces generic strings in the generated RESTAPI.md.
    *
    * @param content The contents of the RESTAPI.md.
    * @return The contents with generic replacements.
    */
  private def replaceGenerics(content: String): String =
    GenericReplacements.foldRight(content)(replaceGeneric)

  /**
    * Replaces a single generic string in the generated RESTAPI.md.
    *
    * @param tokens  The original and replacement strings.
    * @param content The contents of the RESTAPI.md.
    * @return The contents with generic replacements.
    */
  private def replaceGeneric(tokens: (String, String), content: String): String = {
    val (original, replacement) = tokens
    content.replace(original, replacement)
  }

  /**
    * Apache commons tries to dynamically load classes from the current thread classloader. Unfortunately the
    * classloader in the current thread from SBT does NOT have all of the libraries loaded.
    *
    * So-- hack in ClassUtils' classloader instead, that has access to all the required libraries including
    * org.apache.commons.configuration2.PropertiesConfiguration
    *
    * Otherwise, a ClassNotFoundException will occur.
    */
  private def withPatchedClassLoader[A](block: => A): A = {
    val classUtilsClassLoader = classOf[ClassUtils].getClassLoader
    val currentThread = Thread.currentThread
    val originalThreadClassLoader = currentThread.getContextClassLoader
    try {
      currentThread.setContextClassLoader(classUtilsClassLoader)
      block
    } finally
      currentThread.setContextClassLoader(originalThreadClassLoader)
  }

  private def getModifiedMarkdown: String =
    withPatchedClassLoader {
      val config = new Swagger2MarkupConfigBuilder()
        .withMarkupLanguage(MarkupLanguage.MARKDOWN)
        .build()
      val converter = Swagger2MarkupConverter
        .from(SwaggerYamlFile.toPath)
        .withConfig(config)
        .build()
      val contents = converter.toString
      replaceGenerics(replacePaths(contents))
    }

  /**
    * Generates the markdown from the swagger YAML, with some Cromwell customizations.
    */
  private def writeModifiedMarkdown(): Unit = {
    val replacedContents = preamble() + getModifiedMarkdown
    IO.write(RestApiMarkdownFile, replacedContents)
  }

  /**
    * Compares the generated markdown against the existing markdown.
    */
  private def checkModifiedMarkdown(log: Logger): Unit = {
    val markdownContents = StringUtils.substringAfter(IO.read(RestApiMarkdownFile), "-->" + System.lineSeparator)
    val generatedContents = getModifiedMarkdown
    if (markdownContents != generatedContents) {
      val message = "The file docs/api/RESTAPI.md is not up to date. Please run: sbt generateRestApiDocs"
      // Throwing the exception below should log the message... but it doesn't on sbt 1.2.1, so do it here.
      log.error(message)
      throw new IllegalStateException(message)
    } else {
      log.info("checkRestApiDocs: The file docs/api/RESTAPI.md is up to date.")
    }
  }

  // Returns a settings including the `generateRestApiDocs` task.
  val generateRestApiDocsSettings: Seq[Setting[_]] = List(
    generateRestApiDocs := writeModifiedMarkdown(),
    checkRestApiDocs := checkModifiedMarkdown(streams.value.log)
  )
}
