/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.recorder.har

import java.net.{ URI, URL, URLEncoder }

import scala.concurrent.duration.DurationLong
import scala.io.Source
import scala.util.Try

import org.joda.convert.StringConvert
import org.joda.time.DateTime

import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.util.StringHelper.RichString
import io.gatling.http.Headers.Names.CONTENT_TYPE
import io.gatling.recorder.scenario.{ PauseElement, RequestElement, ScenarioElement }
import io.gatling.recorder.util.FiltersHelper.isRequestAccepted
import io.gatling.recorder.util.RedirectHelper.{ isRequestInsideRedirectChain, isRequestRedirectChainEnd, isRequestRedirectChainStart }

object HarReader {

	var scenarioElements: List[ScenarioElement] = Nil

	private var lastEntry: Entry = _
	private var lastStatus: Int = 0
	private var lastRequestTimestamp: Long = 0

	def processHarFile(path: String) {
		def isValidURL(url: String) = Try(new URL(url)).isSuccess

		val json = Source.fromFile(path).getLines.mkString
		val httpArchive = HarMapping.jsonToHttpArchive(json)
		httpArchive.log.entries.filter(entry => isValidURL(entry.request.url)).foreach(processEntry)

	}

	def cleanHarReaderState {
		scenarioElements = Nil
		lastEntry = null
		lastStatus = 0
		lastRequestTimestamp = 0
	}

	private def processEntry(entry: Entry) {

		def buildHeaders(entry: Entry) = {
			val headers = entry.request.headers.map(header => (header.name, header.value)).toMap
			// NetExport doesn't add Content-Type to headers when POSTing, but both Chrome Dev Tools and NetExport set mimeType
			entry.request.postData.map(postData => headers.updated(CONTENT_TYPE, postData.mimeType)).getOrElse(headers)
		}

		def createRequest(entry: Entry, statusCode: Int) {
			def buildContent(postParams: Seq[PostParam]) = {
				def encode(s: String) = URLEncoder.encode(s, configuration.core.encoding)

				postParams.map(postParam => encode(postParam.name) + "=" + encode(postParam.value)).mkString("&")
			}

			val uri = entry.request.url
			val method = entry.request.method
			val headers = buildHeaders(entry)
			// NetExport doesn't copy post params to text field
			val content = entry.request.postData.map(postData => postData.text.trimToOption.getOrElse(buildContent(postData.params)))
			scenarioElements = new RequestElement(new URI(uri), method, headers, content, statusCode, None) :: scenarioElements
		}

		def createPause {
			def parseMillisFromIso8601DateTime(time: String) = StringConvert.INSTANCE.convertFromString(classOf[DateTime], time).getMillis

			val timestamp = parseMillisFromIso8601DateTime(entry.startedDateTime)
			val diff = timestamp - lastRequestTimestamp
			if (lastRequestTimestamp != 0 && diff > 10) {
				scenarioElements = new PauseElement(diff milliseconds) :: scenarioElements
			}
			lastRequestTimestamp = timestamp
		}

		if (isRequestAccepted(entry.request.url, entry.request.method)) {
			if (isRequestRedirectChainStart(lastStatus, entry.response.status)) {
				createPause
				lastEntry = entry

			} else if (isRequestRedirectChainEnd(lastStatus, entry.response.status)) {
				// process request with new status
				createRequest(lastEntry, entry.response.status)
				lastEntry = null

			} else if (!isRequestInsideRedirectChain(lastStatus, entry.response.status)) {
				// standard use case
				createPause
				createRequest(entry, entry.response.status)
			}
			lastStatus = entry.response.status
		}
	}

}
