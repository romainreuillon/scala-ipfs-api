package org.ipfs.api

import java.io._
import java.net.{HttpURLConnection, URLEncoder, URL}
import java.nio.file.{Paths, Path}
import collection.JavaConverters._

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.util.Random

import scala.collection.mutable

import Client._

import scala.io.BufferedSource

class Client(val host : String,
             val port: Int = 5001,
             val base: String = "/api/v0",
             val protocol: String = "http") {

  //TODO
  //pin,  mount

  //
  //
  //basic commands
  //

  def add(paths: Seq[Path]) : Seq[Add] = jsonMapper.reader(classOf[Add])
    .readValues(upload("/add", paths).reader())
    .readAll()
    .asScala

  def addTree(path: Path) = add(walkTree(path))

  def cat(key: String) : InputStream = getRequestInputStream("/cat", toArgs(key))

  def get(key: String) : InputStream = getRequestInputStream("/get", toArgs(key))

  def ls(key:  String): Ls =  getRequestAsType("/ls", classOf[Ls], toArgs(key))

  def refs(key: String): Seq[Ref] = getRequestAsSeq("/refs", classOf[Ref], toArgs(key))

  def blockGet(key: String) : InputStream = getRequestInputStream("/block/get",  toArgs(key))

  //
  //data structure commands
  //

  def blockStat(key: String): BlockStat = getRequestAsType("/block/stat", classOf[BlockStat], toArgs(key))

  def blockPut(key: String, in: InputStream) = upload("/block/put", Seq((key, in)))

  def objectData(key : String) : InputStream = getRequestInputStream("/object/data", toArgs(key))

  def objectGet(key: String) : ObjectGet = getRequestAsType("/object/get", classOf[ObjectGet], toArgs(key))

  def objectStat(key: String) : ObjectStat = getRequestAsType("/object/stat", classOf[ObjectStat], toArgs(key))

  def objectLinks(key: String): Object  = getRequestAsType("/object/links", classOf[Object], toArgs(key))

  def objectPut(path: Path) :  Object = {
    val paths : Seq[Path] = Seq(path)
    jsonMapper.readValue(upload("/object/put", paths).reader(), classOf[Object])
  }

  def fileLs(key: String) : FileLs = getRequestAsType("/file/ls", classOf[FileLs], Seq("arg" -> key))

  def gc {getRequestSource("/repo/gc", Seq())}

  //
  //advanced commands
  //

  def resolve(key: String)  : Resolve =  getRequestAsType("/name/resolve", classOf[Resolve], Seq("arg" -> key))

  def publish(key: String) : Publish = getRequestAsType("/name/publish", classOf[Publish], Seq("arg" -> key))

  def dnsResolve(address: String) : String = getRequestAsJson("/dns", toArgs("ipfs.io")).get("Path").asText()

  def id : Id = getRequestAsType("/id", classOf[Id])

  //
  //network  commands
  //

  def bootstrap : Bootstrap = getRequestAsType("/bootstrap", classOf[Bootstrap])

  def swarmPeers: SwarmPeers = getRequestAsType("/swarm/peers", classOf[SwarmPeers])

  def swarmAdds: SwarmAddrs = getRequestAsType("/swarm/addrs", classOf[SwarmAddrs])

  def swarmConnect(address: String) : JsonNode = getRequestAsJson("/swarm/connect", toArgs(address))

  def swarmDisconnect(address: String) : JsonNode = getRequestAsJson("/swarm/disconnect", toArgs(address))

  def ping(key: String) : Seq[Ping] = getRequestAsSeq("/ping", classOf[Ping], toArgs(key))


  def dhtPut(key: String, value: String) : Seq[DHTResponse] =  getRequestAsSeq("/dht/put", classOf[DHTResponse], Seq("arg" -> key, "arg" -> value))

  def dhtGet(key: String) : DHTResponse = getRequestAsType("/dht/get", classOf[DHTResponse], toArgs(key))

  def dhtFindProvs(key: String) : JsonNode = getRequestAsJson("/dht/findprovs", toArgs(key))

  def dhtFindPeers(peerId:  String) : JsonNode = getRequestAsJson("/dht/findpeers", toArgs(peerId))

  def dhtQuery(peerId:  String) : JsonNode = getRequestAsJson("/dht/query", toArgs(peerId))

  def configShow : ConfigShow =  getRequestAsType("/config/show", classOf[ConfigShow])


  //
  //tool commands
  //

  def version : String =  getRequestAsJson("/version", Seq()).get("Version").asText()

  private def buildUrl(stem: String, query: Seq[(String, String)]) : URL = Client.buildUrl(protocol, host, port, base, stem, query)

  private def  getRequestInputStream(stem: String, query: Seq[(String, String)]) = {
    val url = buildUrl(stem, query)
    url.openConnection().asInstanceOf[HttpURLConnection].getInputStream
  }

  private def getRequestAsType[T](stem: String, clazz: Class[T], query: Seq[(String, String)] = Seq()): T = {
    jsonMapper.readValue(getRequestSource(stem, query).reader(), clazz)
  }
  private def getRequestAsSeq[T](stem: String, clazz: Class[T], query: Seq[(String, String)] = Seq()) : Seq[T] = {
    //necessary for a few IPFS API calls that  return a concatenated sequence of json docs instead of a
    //valid JSON doc
    jsonMapper.reader(clazz)
      .readValues(getRequestSource(stem, query).reader())
      .readAll()
      .asScala
  }

  private def getRequestSource(stem: String, query: Seq[(String, String)]) = {
    val url = buildUrl(stem, query)
    scala.io.Source.fromURL(url)
  }

  private def getRequestAsJson(stem: String, query : Seq[(String, String)]) : JsonNode = jsonMapper.readTree(getRequestSource(stem, query).reader())

  private def upload(stem: String, namedInputStreams: Seq[(String, InputStream)])  : BufferedSource = {
    val url = buildUrl(stem, Seq("stream-channels" -> "true"))

    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setDoOutput(true)
    conn.setDoInput(true)
    conn.setUseCaches(false)
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
    conn.setRequestProperty("User-Agent", "Scala IPFS Client")

    val out = conn.getOutputStream
    val writer = new PrintWriter(new OutputStreamWriter(out, "UTF-8"), true)

    val add = (name: String,  in : InputStream) => {
      val headers: Seq[String] = Seq(
        "--" + boundary,
        "Content-Disposition: file; name=\""+name+"\"; filename=\""+ name+"\"",
        "Content-Type: application/octet-stream",
        "Content-Transfer-Encoding: binary")

      headers.foreach(writer.append(_).append(LINE))
      writer.append(LINE)
      writer.flush()
      try {
        val buffer = new Array[Byte](0x1000)
        var nRead = 0
        while ( {
          nRead = in.read(buffer); nRead
        } != -1)
          out.write(buffer, 0, nRead)
      } finally {
        out.flush()
        writer.append(LINE)
        writer.flush()
        in.close
      }
    }

    namedInputStreams.foreach(e  => add(e._1, e._2))

    Seq("--", boundary, "--", LINE).foreach(writer.append(_))
    writer.close

    io.Source.fromInputStream(conn.getInputStream)
  }

  lazy private val boundary = {
    val random = new Random()
    (0 to 32).map(_ => (0x41 + random.nextInt(26)).asInstanceOf[Char]).toArray.mkString
  }
}

case class Add(Name: String, Hash: String)

case class SwarmPeers(Strings: List[String])

case class BlockStat(Key: String, Size: Int)

case class Link(Name: String,  Hash: String, Size: Int, Type: String)
case class Object(Hash: String, Size: Option[Int], Type: Option[String], Links: Seq[Link])
case class ObjectGet(Links:Seq[Link], Data: String)
case class ObjectStat(Hash: String, NumLinks: Int,  BlockSize: Int, LinksSize: Int,  DataSize: Int,  CumulativeSize: Int)

case class Ls(Objects: Seq[Object])

case class Id(ID: String,  PublicKey: String,  Addresses: List[String], AgentVersion: String, ProtocolVersion: String)

case class Bootstrap(Peers: List[String])

case class Addrs() {
  val map = new mutable.HashMap[String, Seq[String]]()
  @JsonAnySetter def set(key: String, addrs: Seq[String]) {map.put(key, addrs)}
}
case class SwarmAddrs(Addrs: Addrs)

case class Resolve(Path: String)
case class Publish(Name: String, Value:  String)
case class Identity(PeerID: String,  PrivKey: String)
case class Datastore(Type: String,  Path: String)
case class Addresses(Swarm:  Seq[String], API: String,  Gateway:String)
case class Mounts(IPFS: String,  IPNS: String,  FuseAllowOther: Boolean)
case class Version(Current: String, Check: String, CheckDate: String,  CheckPeriod: String, AutoUpdate: String)
case class MDNS(Enabled: Boolean, Interval: Int)
case class Discovery(MDNS: MDNS)
case class Tour(Last:String)
case class Gateway(HTTPHeaders: String, RootRedirect: String, Writable: Boolean)
case class SupernodeRouting(Servers: Seq[String])
case class API(HTTPHeaders: String)
case class Swarm(AddrFilters: String)
case class Log(MaxSizeMB: Int, MaxBackups: Int, MaxAgeDays: Int)
case class ConfigShow(Identity: Identity,
                      Datastore: Datastore,
                      Addresses: Addresses,
                      Mounts: Mounts,
                      Version: Version,
                      Discovery: Discovery,
                      Bootstrap: Seq[String],
                      Tour: Tour,
                      Gateway: Gateway,
                      SupernodeRouting: SupernodeRouting,
                      API: API,
                      Swarm: Swarm,
                      Log: Log)

case class Ref(Ref: String, Err: String)

case class Ping(Success: String, Time: Int, Text:  String)

case class Arguments() {
  val map = new mutable.HashMap[String, String]()
  @JsonAnySetter def set(key: String, value: String) {map.put(key, value)}
}
case class Objects() {
  val map = new mutable.HashMap[String, Object]()
  @JsonAnySetter def set(key: String, o: Object) {map.put(key, o)}
}
case class FileLs(Arguments: Arguments, Objects: Objects)

case class DHTResponseAddrs(Addrs: Seq[String],  ID: String)
case class DHTResponse(Extra: String, ID: String, Responses: Seq[DHTResponseAddrs], Type: Int)

object Client {
  private val jsonMapper = new ObjectMapper()
  jsonMapper.registerModule(DefaultScalaModule)

  private val LINE = "\r\n"


  class FullyReadableInputStream(in: InputStream) {
    def toArray : Array[Byte] = {
      val out = new ByteArrayOutputStream()
      try {
        val buff  = new Array[Byte](0x1000)
        var nRead = 0
        while ( {nRead = in.read(buff);nRead} != -1)
          out.write(buff, 0, nRead)
      } finally {
        in.close
      }
      out.toByteArray
    }
  }

  def toArgs(key: String*) : Seq[(String, String)] = key.map(("arg" -> _))

  def urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")

  implicit def pathToFile(path: Path) = path.toFile

  implicit def inputStreamToFullyReadableInputStream(in: InputStream) = new FullyReadableInputStream(in)

  implicit def pathsToNamedInputStreams(paths:  Seq[Path]) : Seq[(String, InputStream)] = paths.map(path  => (path.getFileName.toString,  new FileInputStream(path)))

  def walkTree(path: Path) : Seq[Path]  = path match {
    case _ if path.isFile => Seq(path)
    case _ if path.isDirectory => path.listFiles().flatMap(f => walkTree(f.toPath))
    case _ => Seq()
  }

  def buildUrl(protocol: String,
               host: String,
               port: Int,
               base: String,
               stem: String,
               query : Seq[(String, String)]) = {

    val queryStem = "?" + query.map(e => urlEncode(e._1) +"="+ urlEncode(e._2)).mkString("&")
    val path = base + stem + queryStem
    new URL(protocol, host, port, path)
  }

  def main(args: Array[String]) = {

    val client = new Client("localhost")

    val sep = () => println("*"*50)


    val paths = Seq("build.sbt", "README.md").map(Paths.get(_))
    val add = client.add(paths)
    println(add)

    sep()

    val addedHash: String = add.head.Hash

    val published: String = "Qmdc5rHtRJEdvde9wecKGLmEoHZRqxbvv1Bb6jFHSMKvZZ"
    val resolve = client.resolve(published)
    println(resolve)

    val cat: InputStream = client.cat(addedHash)
    println(io.Source.fromInputStream(cat).mkString)

    sep()

    val get: InputStream = client.get(addedHash)
    println(io.Source.fromInputStream(get).mkString)
    sep()

    val pinls =  client.getRequestSource("/pin/ls", Seq()).mkString
    println(pinls)
    sep()

    val  id =  client.id
    println(id)
    sep()

    val  bootstrap =  client.bootstrap
    println(bootstrap)
    sep()

    val swarmAddrs = client.swarmAdds
    println(swarmAddrs.Addrs.map)
    sep()

    val gc = client.gc
    println(gc)
    sep()

    val configShow = client.configShow
    println(configShow)
    sep()

    val version = client.version
    println(version)
    sep()

    val ls = client.ls(addedHash)
    println(ls)
    sep()

    val swarmPeers = client.swarmPeers
    println(swarmPeers)
    sep()

    val blockGet =  client.blockGet(addedHash).toArray

    println(blockGet.length)
    sep()

    val  objectData = client.objectData(addedHash).toArray
    println(objectData.length)
    sep()

    val objectLinks =  client.objectLinks(addedHash)
    println(objectLinks)
    sep()

    val objectGet = client.objectGet(addedHash)
    println(objectGet)
    sep()

    val objectStat = client.objectStat(addedHash)
    println(objectStat)
    sep()

    val head = Paths.get("/","home", "chrirs", "camping.md")
    val blockPut = client.blockPut(head.getFileName.toString, new FileInputStream(head.toFile))
    println(blockPut.mkString)
    sep()

    val objectPutPath: Path = Paths.get("/", "home", "chrirs", "node.json")
    val objectPut  = client.objectPut(objectPutPath)
    println(objectPut)
    sep()

    val fileLs = client.fileLs(addedHash)
    println(fileLs.Objects.map)
    sep()

    val publish = client.publish(addedHash)
    println(publish)
    sep()

    val dhtKey: String = "ckey3"
    val  dhtput  = client.dhtPut(dhtKey, "cval3")
    println(dhtput)
    sep()

    val dhtGet = client.dhtGet(dhtKey)
    println(dhtGet)
    sep()

    val dns = client.dnsResolve("ipfs.io")
    println(dns)
  }

}
