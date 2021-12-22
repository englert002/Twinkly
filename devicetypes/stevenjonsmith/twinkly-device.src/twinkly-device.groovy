/**
 *
 *  Twinkly Device Handler
 *
 *  Copyright 2020 Steven Jon Smith with Modifications by Jonathon Bischof and Andrew Hunt
 *
 *  Please read carefully the following terms and conditions and any accompanying documentation
 *  before you download and/or use this software and associated documentation files (the "Software").
 *
 *  The authors hereby grant you a non-exclusive, non-transferable, free of charge right to copy,
 *  modify, merge, publish, distribute, and sublicense the Software for the sole purpose of performing
 *  non-commercial scientific research, non-commercial education, or non-commercial artistic projects.
 *
 *  Any other use, in particular any use for commercial purposes, is prohibited. This includes, without
 *  limitation, incorporation in a commercial product, use in a commercial service, or production of other
 *  artefacts for commercial purposes.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 *  LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 *  NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 *  OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *  You understand and agree that the authors are under no obligation to provide either maintenance services,
 *  update services, notices of latent defects, or corrections of defects with regard to the Software. The authors
 *  nevertheless reserve the right to update, modify, or discontinue the Software at any time.
 *
 *  The above copyright notice and this permission notice shall be included in all copies or substantial portions
 *  of the Software. You agree to cite the Steven Jon Smith in your notices.
 *
 */

metadata {
	definition (name: "Twinkly Device", namespace: "StevenJonSmith", author: "Steven Jon Smith") 
    {
        capability "Color Control"
        capability "Polling"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        
        attribute "authToken", "string"
        attribute "authVerified", "boolean"
	}
    
    preferences {
        input("deviceIP", "string", title: "Device IP Address", description: "Device's IP address", required: true, displayDuringSetup: true)
	}

	simulator 
    {
		// TODO: define status and reply messages here
	}

	tiles(scale: 2)
    {
    	multiAttributeTile(name: "main", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
        	tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'${name}', action:"switch.off", icon:"st.Seasonal Winter.seasonal-winter-011", backgroundColor:"#00a0dc", nextState:"off"
                attributeState "off", label:'${name}', action:"switch.on", icon:"st.Seasonal Winter.seasonal-winter-011", backgroundColor:"#ffffff", nextState:"on"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
		}
        
        standardTile("refresh", "", width: 2, height: 2, decoration: "flat") {
            state "off", label: 'Refresh', action: "poll", icon: "", backgroundColor: "#ffffff"
        }
        
        main (["main"])
		details(["main", "refresh"])
	}
}

def installed()
{
 	unschedule()
    runEvery1Hour(login)
 	runEvery5Minutes(poll)
    login()
 	runIn(4, poll)
}

def updated()
{
	log.debug "Updated..."
    installed()
}

def refresh()
{
	log.debug "Refreshed..."
	poll()
}

def poll()
{
	if (!device.currentValue("authVerified")) {
    	log.debug "Auth not verified, delaying poll."
        return
    }
    
    sendHubCommand(createAction("query", "summary", null, xledSummaryCallback))
}

def on()
{
	log.debug "Executing 'On'"
    sendHubCommand(createAction("action", "led/mode", "{\"mode\":\"movie\"}", xledModePostCallback))
}

def off()
{
	log.debug "Executing 'Off'"
    sendHubCommand(createAction("action", "led/mode", "{\"mode\":\"off\"}", xledModePostCallback))
}

def setLevel(brightness) {
	log.debug "Executing 'setLevel'"
    state.level = brightness
    sendHubCommand(createAction("action", "led/out/brightness", "{\"value\":${brightness},\"type\":\"A\",\"mode\":\"enabled\"}", xledModePostCallback))
}

def xledModePostCallback(output) {
	def ret = parse(output)
    if (ret["code"] != 0) {
    	log.error "Failed to post xled change"
       	return
    }
    
    poll()
}

def xledSummaryCallback(output) {
	log.debug "xledModeCallback called!"
    
	def ret = parse(output)
    def body = ret['json']
    
    log.debug body
    
    def mode = body['led_mode']['mode']
	if (mode == "off" || mode == "disabled") {
        sendEvent(name:'switch', value:"off", displayed:true)
    } else {
        sendEvent(name:'switch', value:"on", displayed:true)
    }
    
    for (filter in body['filters']) {
    	if (filter['filter'] == 'brightness') {
        	def brightness = filter['config']['value']
            sendEvent(name:'level', value:brightness, displayed:true)
        }
    }
}

def login() {
	log.debug "Login() called"
	sendHubCommand(createAction("auth", null, null, loginCallback))
}

def loginCallback(output) {
    log.debug "Login callback called!"
    
    def ret = parse(output)

    if (ret['code'] != 0) {
    	log.error "Failed to login"
        return
    }
    
    if (ret['json'].containsKey("authentication_token")) {
        def token = null
        token = ret['json']['authentication_token']

        if (token != null && token != "") {
            log.debug "Auth Token: $token"
            sendEvent(name:'authToken', value:token, displayed:false)
            sendEvent(name:'authVerified', value:false, displayed:false)

            verify()
        }
    }
}

def verify() {
	log.debug "Verify() called after login"
	sendHubCommand(createAction("verify", null, null, verifyCallback))
}

def verifyCallback(output) {
	log.debug "Verify callback called!"
    
    def ret = parse(output)
    if (ret['code'] != 0) {
    	log.error "Failed to validate auth token"
    } else {
    	log.debug "Successfully validated auth token"
    	sendEvent(name:'authVerified', value:true, displayed:false)
    }
}

def check() {

}

def reset() {
	sendHubCommand(createAction("action", "led/reset"))
}

def createAction(String cmd, String endpoint = null, body = null, callbackMethod = parse) {
    def path = "/xled/v1/"
    def httpRequest = [
        headers: [
			HOST: "$deviceIP:80"
		]
	]
    
    if (cmd == "query") {
    	httpRequest.put('method', "GET")
    } else {
		httpRequest.put('method', "POST")
    }
    
    if (cmd == "auth") {
    	def challenge = generateChallenge()
        path = path + "login"
        httpRequest.put('path', path)
        httpRequest.put('body', "{\"challenge\":\"$challenge\"}")
    } else if (cmd == "verify") {
    	path = path + "verify"
        httpRequest.put('path', path)
        httpRequest['headers'].put("X-Auth-Token", device.currentValue("authToken"))
    } else if (cmd == "action") {
    	path = "$path$endpoint"
        httpRequest.put('path', path)
        httpRequest['headers'].put("X-Auth-Token", device.currentValue("authToken"))
        httpRequest.put('body', "$body")
    } else if (cmd == "query") {
    	path = "$path$endpoint"
        httpRequest.put('path', path)
        httpRequest['headers'].put("X-Auth-Token", device.currentValue("authToken"))
    }
    
    try 
    {
    	def hubAction = new physicalgraph.device.HubAction(httpRequest, device.deviceNetworkId, [callback: callbackMethod])
        log.debug "Created action: $hubAction"
        return hubAction
    }
    catch (Exception e) 
    {
		log.debug "Hit Exception $e on $hubAction"
	}
}

def parse(output) {
	log.debug "Starting response parsing on ${output}"
    
    def headers = ""
	def parsedHeaders = ""
    
    def msg = output
    
    def ret = [:]
    ret['code'] = -1
    ret['output'] = msg

    def body = msg.body
    
    if (body != "Invalid Token") { 
    	body = new groovy.json.JsonSlurper().parseText(body)
        ret['json'] = body
    } else {
    	log.error "Invalid Token, returning L213, parse"
        ret['code'] = -2
        
        login()
    	return ret
    }
    
    if (body == "Invalid Token") {
        log.error "Invalid Token, returning L213, parse"
        ret['code'] = -2
        return ret
    }
    
    if (body['code'] == 1000) {
        ret['code'] = 0
        return ret
    }
    
    log.debug "Unable to locate device on your network"
    return ret
}

private def generateChallenge() {
	def pool = ['a'..'z','A'..'Z',0..9].flatten()
    def rand = new Random()
    def challenge = ""
    
    for (def i = 0; i < 32; i++) {
    	challenge = challenge + pool[rand.nextInt(pool.size())]
	}
    log.debug "Created challenge: $challenge"
    
    challenge = challenge.bytes.encodeBase64()
    log.debug "Encoded challenge: $challenge"

	return challenge
}

private def pause(millis) {
   def passed = 0
   def now = new Date().time

   if ( millis < 18000 ) {
       while ( passed < millis ) {
           passed = new Date().time - now
       }
   }
}