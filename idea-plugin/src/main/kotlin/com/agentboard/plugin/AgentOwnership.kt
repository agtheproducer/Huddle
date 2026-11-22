package com.agentboard.plugin

import com.agentboard.core.BoardState

/** HTTP ownership is established by the server on the first verified post under a name. */
internal fun BoardState.verifiedOwners(): Map<String, String> = buildMap {
    for (post in timeline) post.verifiedLogin?.let { put(post.event.agent, it) }
}

internal fun ownsAgent(agent: String?, primary: String, login: String?, owners: Map<String, String>): Boolean {
    if (agent.isNullOrBlank()) return false
    val owner = owners[agent]
    if (login != null && owner != null) return owner.equals(login, ignoreCase = true)
    return agent == primary || agent == AutoReply.humanOf(primary) && primary.isNotBlank()
}
