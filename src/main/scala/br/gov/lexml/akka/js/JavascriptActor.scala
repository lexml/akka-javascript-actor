package br.gov.lexml.akka.js

import akka.actor.Actor
import org.dynjs.Config
import org.dynjs.runtime.DynJS
import org.dynjs.runtime.Runner
import java.io.InputStreamReader
import org.dynjs.runtime.JSProgram
import java.io.File
import akka.actor.ActorContext
import org.dynjs.exception.DynJSException
import akka.actor.ActorRef
import java.io.PrintStream
import br.gov.lexml.eventfilteroutputstream.EventFilterOutputStream
import br.gov.lexml.eventfilteroutputstream.Event
import akka.actor.ActorLogging

abstract sealed class JavascriptSource {
  def applyTo(runner : Runner) : Runner
}

final case class JSS_String(src : String) extends JavascriptSource {
  override def applyTo(runner : Runner) = runner.withSource(src)
}

final case class JSS_Program(src : JSProgram) extends JavascriptSource {
  override def applyTo(runner : Runner) = runner.withSource(src)
}

final case class JSS_File(src : File) extends JavascriptSource {
  override def applyTo(runner : Runner) = runner.withSource(src)
}

final case class JSS_CPResource(resourceName : String, classloader : Option[ClassLoader] = None, encoding : String = "utf-8") extends JavascriptSource {
  override def applyTo(runner : Runner) = {
    val cl = classloader getOrElse (Thread.currentThread().getContextClassLoader())
    runner.withSource(new InputStreamReader(cl.getResourceAsStream(resourceName), encoding))

  }
}

abstract sealed class JavascriptMessage 

case object JS_Reset extends JavascriptMessage 

/**
 * Executa statemp, não é a intenção obter o resultado
 */
final case class JS_Execute(src : JavascriptSource) extends JavascriptMessage

/**
 * Execução para obtenção de um resultado (avaliar uma expressão)
 */
final case class JS_Evaluate(src : JavascriptSource) extends JavascriptMessage

final case class JS_SubscribeForOutput(ref : Option[ActorRef] = None) extends JavascriptMessage

final case class JS_UnsubscribeForOutput(ref : Option[ActorRef] = None) extends JavascriptMessage

final case class JS_SubscribeForError(ref : Option[ActorRef] = None) extends JavascriptMessage

final case class JS_UnsubscribeForError(ref : Option[ActorRef] = None) extends JavascriptMessage

abstract sealed class JavascriptReply

case object JSR_Reseted extends JavascriptReply

final case class JSR_ResultOK(result : Any)

final case class JSR_Exception(exception : DynJSException)

class JavascriptActor(sources : Seq[JavascriptSource] = Seq(), config : Config = new Config()) extends Actor with ActorLogging {
  private var jsInterpreter : DynJS = _
  
  private var outputListeners : Set[ActorRef] = Set()
  
  private var errorListeners : Set[ActorRef] = Set()
  
  def eventHandler(listeners : => Set[ActorRef]) : Event => Unit = { ev =>
    log.debug("eventHandler: dispatching event: " + ev)
    listeners foreach (_ ! ev)
  }
  
  config.setOutputStream(new PrintStream(new EventFilterOutputStream(eventHandler(outputListeners))))
  
  config.setErrorStream(new PrintStream(new EventFilterOutputStream(eventHandler(errorListeners))))
  
  private def runner() = {
    log.debug("runner: creating a new runner")
    jsInterpreter.newRunner().withContext( jsInterpreter.getDefaultExecutionContext() )
  }
  
  override def preStart() {
    log.info("preStgart: starting the actor: " + self)
    jsInterpreter = new DynJS(config)    
    sources.foreach(self ! JS_Execute(_))
  }
 
  private def doOrException(src : JavascriptSource)(f : Runner => Any) : Unit = {
    try {
        log.debug("doOrException: going to execute")
        val r = f(src.applyTo(runner()))
        log.debug("doOrException: executed. result: " + r)
        sender ! JSR_ResultOK(r)
      } catch {
        case ex : DynJSException => 
           log.debug("doOrException: exception occurred:" + ex)
           sender ! JSR_Exception(ex)
      }
  }
  
  
  override def receive : Receive = {
    case JS_Reset => log.info("receive: resetting the Javascript VM")
                     jsInterpreter = new DynJS(config)
                     sender() ! JSR_Reseted
    case JS_Execute(src) => doOrException(src)(_.execute())  
    case JS_Evaluate(src) => doOrException(src)(_.evaluate())    
    case JS_SubscribeForOutput(ref) => outputListeners += ref.getOrElse(sender())
    case JS_UnsubscribeForOutput(ref) => outputListeners -= ref.getOrElse(sender())
    case JS_SubscribeForError(ref) => errorListeners += ref.getOrElse(sender())
    case JS_UnsubscribeForError(ref) => errorListeners -= ref.getOrElse(sender())
  }
}