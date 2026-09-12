/*
 * Copyright (c) 2026, osrsloadout
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrsloadout;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * Everything about the link between this install and osrsloadout.com: the two endpoints it calls and the
 * shape of what goes over them.
 *
 * Kept separate from the plugin, and free of RuneLite types, because the request bodies are the part worth
 * testing and a JSON shape can be asserted without a game client.
 *
 * The identity of a bank is the secret, and only the secret. The display name travels with it as a label so
 * the site has something to show, but it is not a lookup key and nothing here should ever treat it as one:
 * that is the whole difference between this design and the previous one, where knowing a character's name
 * was enough to read their bank.
 */
final class LoadoutLink
{
	static final String UPLOAD_ENDPOINT = "https://yqdqbsbgowqjkjplkrzi.supabase.co/functions/v1/bank";
	static final String PAIR_ENDPOINT = UPLOAD_ENDPOINT + "/pair";

	private LoadoutLink()
	{
	}

	/**
	 * Gson rather than string concatenation because a display name is attacker-adjacent input: it arrives
	 * from the game, it can contain a non-breaking space, and hand-built JSON is how a stray quote turns into
	 * a malformed request nobody can reproduce.
	 */
	static String uploadJson(Gson gson, @Nullable String rsn, Collection<Integer> ids, String secret)
	{
		return gson.toJson(new Upload(rsn, ids, secret));
	}

	static String pairJson(Gson gson, @Nullable String rsn, String secret)
	{
		return gson.toJson(new Pair(rsn, secret));
	}

	/**
	 * Returns the pairing code from a /bank/pair response, or null if the body is not what we expect. Null
	 * rather than an exception because every caller's answer to a malformed response is the same as its
	 * answer to a network failure: say nothing and let the next bank try again.
	 */
	@Nullable
	static String codeFrom(Gson gson, String body)
	{
		try
		{
			final PairResponse parsed = gson.fromJson(body, PairResponse.class);
			if (parsed == null || parsed.code == null || parsed.code.isEmpty())
			{
				return null;
			}
			return parsed.code;
		}
		catch (JsonSyntaxException e)
		{
			return null;
		}
	}

	/**
	 * The field names are the wire contract; Gson takes them verbatim. Renaming one silently changes the
	 * request, which is what the test on this class is guarding. A null rsn is omitted rather than sent as
	 * null, which is what makes the label genuinely optional.
	 */
	private static final class Upload
	{
		private final String rsn;
		private final int[] ids;
		private final String secret;

		private Upload(@Nullable String rsn, Collection<Integer> ids, String secret)
		{
			this.rsn = rsn;
			this.secret = secret;
			this.ids = new int[ids.size()];

			int i = 0;
			for (int id : ids)
			{
				this.ids[i++] = id;
			}
		}
	}

	private static final class Pair
	{
		private final String rsn;
		private final String secret;

		private Pair(@Nullable String rsn, String secret)
		{
			this.rsn = rsn;
			this.secret = secret;
		}
	}

	/** Only {@code code} is read; the server also returns expires_in, which the plugin has no use for. */
	private static final class PairResponse
	{
		private String code;
	}
}
