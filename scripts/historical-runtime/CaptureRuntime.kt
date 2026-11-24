// Authoring-only program, compiled in the pinned historical checkout's test module.
package sotaos.test.p09

import kotlinx.serialization.json.*
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.domain.shared.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectory(output)
    ProductionFixture().use { f ->
        val token = f.enroll()
        val received = f.record("verified-before-retirement")
        f.exchange(token, received)
        f.runtime.keyProvisioning.rotate(invocation, authorityId,
            ActorSigningKeyRecord(remoteActor, "remote-key-2", Ed25519EventSigner.generate().publicKeyEncoded()),
            "remote-key-1")
        f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-2")
        f.runtime.keyProvisioning.enroll(invocation, authorityId,
            ActorSigningKeyRecord(localActor, "local-active-key", Ed25519EventSigner.generate().publicKeyEncoded()))
        f.runtime.peerProvisioning.rotate(invocation, authorityId, peerId, 1)
        val retiredPeer = SotaId("retired-peer")
        f.repositories.authorities.save(f.authority.copy(scope = f.authority.scope.copy(
            resources = f.authority.scope.resources + "peer:retired-peer")))
        f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy.copy(peer = retiredPeer))
        f.runtime.peerProvisioning.rotate(invocation, authorityId, retiredPeer, 1)
        f.runtime.peerProvisioning.revoke(invocation, authorityId, retiredPeer, 2)
        val approved = f.record("approved-offline-history")
        f.approveHistory(approved)
        val revoked = f.record("revoked-offline-history")
        f.approveHistory(revoked)
        f.runtime.historicalApprovals.revoke(invocation, authorityId, revoked.event.id)
        // Public fixture-only bearer vectors replace ephemeral generated tokens before export.
        f.peers.save(requireNotNull(f.peers.find(peerId)).copy(
            credentialHash = PeerCredentials.hash("public-historical-peer-token")))
        f.peers.save(requireNotNull(f.peers.find(retiredPeer)).copy(
            credentialHash = PeerCredentials.hash("public-retired-peer-token")))
        val records = listOf(received, approved, revoked, f.record("unknown-old-key-history"))
        writeJson(output.resolve("vectors.json"), buildJsonObject {
            put("fixtureOnly", true)
            put("records", JsonArray(records.map { JsonPrimitive(JsonSyncRecordCodec().encode(it)) }))
        })
        DriverManager.getConnection("jdbc:sqlite:${f.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) }
            }
            val objects = mutableListOf<JsonObject>()
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT type, name, sql FROM sqlite_master
                    WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                    ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 ELSE 2 END, name""").use { result ->
                    while (result.next()) objects += buildJsonObject {
                        put("type", result.getString(1)); put("name", result.getString(2)); put("sql", result.getString(3))
                    }
                }
            }
            writeJson(output.resolve("runtime-schema.json"), buildJsonObject {
                put("revision", "3c1a4aa1d20cf77179e3c37580ddd682b329d018")
                put("capture", "sqlite_master after historical SqlDelightStore initialization and synthetic P09 operations")
                put("objects", JsonArray(objects))
                put("statements", JsonArray(objects.map { it.getValue("sql") }))
            })
            val rows = mutableListOf<JsonObject>()
            for (table in objects.filter { it.getValue("type").jsonPrimitive.content == "table" }) {
                val name = table.getValue("name").jsonPrimitive.content
                check(name.matches(Regex("[a-z_]+")))
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $name ORDER BY rowid").use { result ->
                        while (result.next()) rows += buildJsonObject {
                            put("table", name)
                            put("values", buildJsonObject {
                                for (index in 1..result.metaData.columnCount) {
                                    val value = result.getObject(index)
                                    put(result.metaData.getColumnName(index), when (value) {
                                        null -> JsonNull
                                        is Number -> JsonPrimitive(value)
                                        else -> JsonPrimitive(value.toString())
                                    })
                                }
                            })
                        }
                    }
                }
            }
            writeJson(output.resolve("data.json"), buildJsonObject {
                put("fixtureOnly", true); put("rows", JsonArray(rows))
            })
            println("Captured ${objects.size} schema objects and ${rows.size} synthetic rows")
        }
    }
}

private fun writeJson(path: Path, value: JsonObject) {
    Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n")
}
