package actor.git

import java.io.{File, FileFilter, FileInputStream}
import java.text.SimpleDateFormat
import java.util.{List => JList, Map => JMap}

import akka.actor.{Actor, ActorLogging}
import models.conf._
import models.task.{TaskTemplate, TaskTemplateHelper, TaskTemplateStep, TaskTemplateStepHelper}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.joda.time.DateTime
import org.yaml.snakeyaml.Yaml
import play.api.Play

import scala.collection.JavaConverters._

/**
 * Created by mind on 7/24/14.
 */
import ScriptGitActor._

object ScriptGitActor {
  case class BuildScriptTag(tagName: String = s"r${DateFormat.format(DateTime.now.toDate)}")
  case class ReloadFormulasTemplate()

  val DateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS")
}

class ScriptGitActor extends Actor with ActorLogging {
  val TemplateSuffix = ".yaml"
  val TemplatePath = "/templates"
  val Ok = "Ok"

  val app = Play.current
  lazy val gitFormulasDir: File = new File(app.configuration.getString("git.formulas.dir").getOrElse("target/formulas"))
  lazy val gitFormulasUrl = app.configuration.getString("git.formulas.url").getOrElse("http://git.dev.ofpay.com/git/TDA/salt-formulas.git")
  lazy val gitPkgsDir: File = new File(app.configuration.getString("git.pkgs.dir").getOrElse("target/pkgs"))
  lazy val gitPkgsUrl = app.configuration.getString("git.pkgs.url").getOrElse("http://git.dev.ofpay.com/git/TDA/salt-pkgs.git")

  var gitFormulas: Git = null
  var gitPkgs: Git = null

  override def preStart(): Unit = {
    // 启动时初始化git目录
    if (app.configuration.getBoolean("git.init").getOrElse(true)) {
      gitFormulas = _initGitDir(gitFormulasDir, gitFormulasUrl)
      gitPkgs = _initGitDir(gitPkgsDir, gitPkgsUrl)
    }
  }

  def _initGitDir(workDir: File, girUrl: String) = {
    val gitWorkDir = new File(s"${workDir.getAbsolutePath}/.git")
    if (!workDir.exists() || !gitWorkDir.exists()) {
      _delDir(workDir)
      val clone = Git.cloneRepository()
      clone.setDirectory(workDir).setURI(girUrl)
      clone.call()
    }

    val builder = new FileRepositoryBuilder()
    val repo = builder.setGitDir(gitWorkDir).build()

    val git = new Git(repo)
    log.info(s"Init git: ${git.getRepository}")
    git
  }

  override def receive: Receive = {
    case ReloadFormulasTemplate => {
      _reloadTemplates
      sender ! Ok
    }
    case BuildScriptTag(tagName) => {
      _tagScript(tagName)
      sender ! Ok
    }
    case x => log.warning(s"Unknown message ${x}")
  }

  def _tagScript(tagName: String) = {
    if (gitFormulas != null && gitPkgs != null) {
      // checkout到master上
      gitFormulas.checkout().setName(ScriptVersionHelper.Master).call()
      gitPkgs.checkout().setName(ScriptVersionHelper.Master).call()

      gitFormulas.pull().call()
      gitPkgs.pull().call()

      gitFormulas.tag().setName(tagName).call()
      gitPkgs.tag().setName(tagName).call()

      gitFormulas.push().setPushTags().call()
      gitPkgs.push().setPushTags().call()

      ScriptVersionHelper.create(ScriptVersion(None, tagName, message = Some("")))
      _loadTemplateFromDir(tagName)
    }
  }

  def _reloadTemplates() {
    if (gitFormulas != null) {
      gitFormulas.checkout().setName(ScriptVersionHelper.Master).call()
      gitFormulas.pull().call()

      val tags = gitFormulas.tagList().call()
      val scriptNames = ScriptVersionHelper.allName()

      if (tags != null) {
        // 加载新的tag脚本
        tags.asScala.map(_.getName.split("/").last).filterNot(scriptNames.contains).foreach { tagName =>
          log.debug(s"Load tag: ${tagName}")
          ScriptVersionHelper.create(ScriptVersion(None, tagName, message = Some("")))

          _loadTemplateFromDir(tagName)
        }
      }

      // 重新加载master,先讲老master更新掉
      log.debug(s"Load tag: ${ScriptVersionHelper.Master}")
      val backupMasterName = s"master-bak-${DateFormat.format(DateTime.now.toDate)}"
      TemplateItemHelper.updateScriptVersion(ScriptVersionHelper.Master, backupMasterName)
      TaskTemplateHelper.updateScriptVersion(ScriptVersionHelper.Master, backupMasterName)

      _loadTemplateFromDir(ScriptVersionHelper.Master)
    } else {
      log.warning("Reload template, but git is null")
    }
  }

  def _loadTemplateFromDir(tagName: String) {
    gitFormulas.checkout().setName(tagName).call()

    val templateDir = new File(s"${gitFormulasDir.getAbsolutePath}${TemplatePath}")
    templateDir.listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = pathname.getName.endsWith(TemplateSuffix)
    }).foreach { file =>
      log.debug(s"Load file: ${file}")
      _initFromYaml(file, tagName)
    }
  }

  def _initFromYaml(file: File, tagName: String) = {
    val yaml = new Yaml()
    val io = new FileInputStream(file)
    val template = yaml.load(io).asInstanceOf[JMap[String, AnyRef]]

    val templateName = template.get("name").asInstanceOf[String]


    val templateId = TemplateHelper.findByName(templateName) match {
      case Some(temp) => {
        val templateId = temp.id.get
        // 更新模板说明
        TemplateHelper.update(templateId, temp.copy(remark = Some(template.get("remark").asInstanceOf[String])))
        templateId
      }
      case None => TemplateHelper.create(Template(None, templateName, Some(template.get("remark").asInstanceOf[String])))
    }

    // 创建template关联的item
    val templateItems = template.get("items")
    if (templateItems != null) {
      templateItems.asInstanceOf[JList[JMap[String, String]]].asScala.zipWithIndex.foreach {
        case (x: JMap[String, String], index) =>
          TemplateItemHelper.create(TemplateItem(None, Some(templateId), x.get("itemName"), Some(x.get("itemDesc")), Some(x.get("default")), index, tagName))
      }
    }

    // 创建template关联的actions
    val actions = template.get("actions")
    if (actions != null) {
      actions.asInstanceOf[JList[JMap[String, AnyRef]]].asScala.zipWithIndex.foreach {
        case (action, index) =>
          val taskId = TaskTemplateHelper.create(TaskTemplate(None, action.get("name").asInstanceOf[String], action.get("css").asInstanceOf[String], action.get("versionMenu").asInstanceOf[Boolean], templateId, index + 1, tagName))
          val steps = action.get("steps").asInstanceOf[JList[JMap[String, String]]].asScala
          steps.zipWithIndex.foreach { case (step, index) =>
            val seconds = step.get("seconds").asInstanceOf[Int]
            TaskTemplateStepHelper.create(TaskTemplateStep(None, taskId, step.get("name"), step.get("sls"), if (seconds <= 0) 3 else seconds, index + 1))
          }
      }
    }
  }

  def _delDir(dir: File) {
    if (dir.exists()) {
      dir.listFiles().foreach { x =>
        if (x.isDirectory()) {
          _delDir(x)
        } else {
          x.delete()
        }
      }
      dir.delete()
    }
  }
}