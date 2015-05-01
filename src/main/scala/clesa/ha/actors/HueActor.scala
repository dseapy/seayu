package clesa.ha.actors

import akka.actor.{ActorRef, Actor}
import clesa.ha.components.Hue
import clesa.ha.events.hue.HueEvent
import clesa.ha.events.linuxinput._
import com.philips.lighting.model.{PHLight, PHHueError}
import org.joda.time.DateTime
import collection.JavaConversions._

class HueActor(broadcastActor: ActorRef,
               ipAddress: String)
  extends Actor {

  var stateKnown = true
  var lastButtonClickTime = new DateTime(0L)
  var lastSwipeFromSideTime = new DateTime(0L)

  //useful for button presses and other non-cumulative actions
  def enoughTimePassedSinceLastEvent(oldTime: DateTime, newTime: DateTime) = oldTime plusMillis 300 isBefore newTime

  val hue = new Hue(hueEvent => broadcastActor ! hueEvent,
                    hueError => self ! hueError,
                    ipAddress,
                    "clesa",
                    "ha",
                    "newdeveloper")

  val sourceToLightIdsMap = Map("/dev/input/event3" -> LightIdsWithActive("1","2",activeLightFirst = true))
  case class LightIdsWithActive(lightId0: String, lightId1: String, var activeLightFirst: Boolean){
    def getActiveLightId = if(activeLightFirst) lightId0 else lightId1
    def getActiveLightFromSource(source: String) = hue.allLights.find(_.getIdentifier == getActiveLightId)
  }
  def getActiveLightOptionFromSource(source: String): Option[PHLight] =
    sourceToLightIdsMap.get(source).flatMap(_.getActiveLightFromSource(source))

  def receive = {
    case te: VWheel => if(stateKnown) stateKnown =
      getActiveLightOptionFromSource(te.source).map{ light =>
        !hue.increaseBrightnessBy(light, te.value * 10)
      }.getOrElse(true)
    case bc: ButtonClick =>
      val enoughTimePassed = enoughTimePassedSinceLastEvent(lastButtonClickTime, bc.datetime)
      lastButtonClickTime = bc.datetime
      if(stateKnown && enoughTimePassed)
        stateKnown = getActiveLightOptionFromSource(bc.source).map{ light => !hue.toggle(light) }.getOrElse(true)
    case rs: SwipeFromSide =>
      println(rs)
      val enoughTimePassed = enoughTimePassedSinceLastEvent(lastSwipeFromSideTime, rs.datetime)
      lastSwipeFromSideTime = rs.datetime
      if(enoughTimePassed) {
        sourceToLightIdsMap.get(rs.source).foreach(_.activeLightFirst = rs.fromRight)
      }
    case he: HueEvent => hue.updateLightState(he)
                         stateKnown = true
    case hError: PHHueError => {
      println(hError.getMessage)
      stateKnown = true
    }
    case other =>
  }
}

object HueActor {
  val name = "HueActor"
}