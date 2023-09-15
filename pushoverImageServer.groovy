/*

Modified by Leon Schwartz - 9-14-23.

Includes code from:
Copyright 2020 - tomw

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-------------------------------------------

Change history:

0.9.2 - tomw - Name/Label display improvements
0.9.1 - tomw - Added 'notify' GET entrypoint for sending Pushover notifications on demand
0.9.0 - tomw - Compatibility for images stored in File Manager
0.1.0 - tomw - Pre-release version

*/

definition(
    name: "Pushover Image Server",
    namespace: "octadox",
    author: "octadox",
    description: "",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")

preferences
{
    page(name: "entryPage")
}

def uninstalled()
{
    unsubscribe()
}

def entryPage()
{
    enableOauth()
    
    dynamicPage(name: "entryPage", title: "", install: true, uninstall: true)
    {
        unsubscribe()
        if(imageGenerators)
        {
            subscribe(imageGenerators, "image", "notificationHandler")
        }
        
        section
        {
            input name:	"imageGenerators", type: "capability.imageCapture", title: "Select devices that generate images you want to send.", multiple: true, required: true, submitOnChange: true
            paragraph("<h3>Use these URLs to access images directly:</h3>")
            
            for(dev in imageGenerators)
            {
                paragraph("<b>${dev.getDisplayName()}:</b> ${getFullLocalApiServerUrl() + "/${imagePath}/${dev.getDeviceNetworkId()}?access_token=${token}"}")
            }
            
            paragraph("<b>Single most recent image out of all of the selected devices:</b> ${getFullLocalApiServerUrl() + "/${latestPath}?access_token=${token}"}")
            input name:	"pushoverDevices", type: "capability.notification", title: "Select devices that you want to notify.", multiple: true, required: true, submitOnChange: true

        }
        
    }
}

def logDebug(msg)
{
    if(enableLogging)
    {
        log.debug "${msg}"
    }
}

private void enableOauth()
{
    // Thanks! - https://github.com/imnotbob/autoMower/blob/main/automower-connect.groovy
    Map params=
        [
            uri: "http://localhost:8080/app/edit/update?_action_update=Update&oauthEnabled=true&id=${app.appTypeId}".toString(),
            headers: ['Content-Type':'text/html;charset=utf-8']
        ]
    try
    {
        httpPost(params) {}
    }
    catch (e) {}
}

def getToken()
{
    if(!state.accessToken)
    {
        createAccessToken()
    }
    
    return state.accessToken
}

import groovy.transform.Field
@Field String imagePath = "image"
@Field String latestPath = "latest"
@Field String notificationPath = "notify"

mappings
{
    path("/${imagePath}/:devDniOrName")
    { action: [GET: "serveImage"] }
    
    path("/${latestPath}")
    { action: [GET: "serveLatest"] }
    
    def notifyHandler = [GET: "notifyOnDemand"]
    
    path("/${notificationPath}/:devDniOrName")
    { action: notifyHandler }
    
    path("/${notificationPath}")
    { action: notifyHandler }
}

def serveImage()
{
    def reqDev = params.devDniOrName
    
    logDebug("Image Server request for ${reqDev}")
    
    try
    {
        def dev = findDevByDniOrName(sourceDevs, params.devDniOrName)
        if(!dev) { throw new Exception("no matching device") }
        
        if(null == dev.currentValue('image'))
        {
            throw new Exception("no image attribute present")
        }
        
        logDebug("Image Server matched ${dev.getDisplayName()}") 
        return render(renderImageMap(dev))
    }
    catch (Exception e)
    {
        return render(contentType: "text/html", data: "Image Server: ${e.message}", status: 200) 
    }
}

def serveLatest()
{
    try
    {
        def time = 0
        def latestDev
        
        if(!sourceDevs?.size()) { throw new Exception("no devices selected") }
        
        for(dev in sourceDevs)
        {
            dev.events().each
            {
                if( invalidImageVals().contains(dev.currentValue('image')?.toString()) ) { return }
                if((it.name == "image"))
                {
                    if(it.getUnixTime() > time)
                    {
                        time = it.getUnixTime()
                        latestDev = dev
                    }
                }
            }
        }
        
        // Fail-safe -- if events have aged out, just use the first device.
        //     This feels like a bug in the Hubitat Device.events() API
        if(null == latestDev) { latestDev = sourceDevs?.getAt(0) }
        
        return render(renderImageMap(latestDev))
    }
    catch (Exception e)
    {
        return render(contentType: "text/html", data: "Image Server: ${e.message}", status: 200) 
    }    
}

def invalidImageVals()
{
    return [null, "n/a"]
}

def renderImageMap(dev)
{
    def imageArr = getImageAttr(dev)
    if(null == imageArr)
    {
        // the file was deleted, or something
        throw new Exception("image missing")
    }
    
    return [contentType: "image/jpeg;base64", data: imageArr, status: 200]
}

def getImageAttr(dev)
{
    def image = dev.currentValue('image')
    
    if(invalidImageVals().contains(image.toString()))
    {        
        throw new Exception("no image present")
    }
    
    if(image?.contains("file:"))
    {
        // new-style images are stored in the File Manager
        //   and the 'image' attribute is "file:" plus the fileName
        def fileName = image.split("file:")?.getAt(1)
        try
        {
            image = downloadHubFile(fileName)
        }
        catch(java.nio.file.NoSuchFileException e)
        {
            // file is missing, even though the 'image' attr said it was there
            return null
        }
        catch(groovy.lang.MissingMethodException e)
        {
            if(e.message.contains("downloadHubFile"))
            {
                // file I/O APIs were added in 2.3.4.132
                def errMsg = "Your camera driver indicates this image is stored in the file manager."
                errMsg += "  You must update your Hubitat software to at least version 2.3.4.132."  
                log.error errMsg
            }
            else { logDebug e }
            
            return null
        }
        catch(e)
        {
            logDebug e
            return null
        }
    }
    else
    {
        // old-style images are stored as a hex string, so convert to byte[] before returning
        image = hubitat.helper.HexUtils.hexStringToByteArray(image)
    }
    
    return image    
}

def notificationHandler(evt)
{
    def imageDev = evt.getDevice()
    def title = evt.getDisplayName()
    def date = evt.getDate()
    if(imageDev.currentValue('image') != "n/a")
    {
        logDebug("new image on device: ${imageDev}")
        pushoverNotification(imageDev, title, "New image: ${date}")
    }
}

def pushoverNotification(imageDev, String title = "", String message = "New Image")
{
    if([imageDev].contains(null))
    {
        logDebug("Missing image device")
        return
    }
    
    def imageArr = getImageAttr(imageDev)
    
    if(!pushoverDevices?.size()) { throw new Exception("No notification devices selected") }
        
    for(pushoverDev in pushoverDevices)
    {
        pushoverDev.sendImage(imageArr, title, message)
    }
}

def findDevByDniOrName(devs, idValue)
{
    if(!idValue) { return }
    
    idValue = java.net.URLDecoder.decode(idValue)
    
    // check for matching DNI first and return if so...
    def dev = devs?.find { it.getDeviceNetworkId()?.toString() == idValue }
    if(dev) { return dev }
    
    // ...if not, then check whether either name or label matches
    // note that this would be first found, since only DNI is globally unique
    dev = devs?.find { [it.getName(), it.getLabel()].contains(idValue) }
    
    return dev
}

def notifyOnDemand()
{
    logDebug("notifyOnDemand(): ${params}")
    
    // only dni/name is absolutely required
    if(!(params.devDniOrName || params.device)) { throw new Exception("must specify device name or DNI") }    
    
    // remove URL decoding from these others, if they exist
    if(params.title) { params.title = java.net.URLDecoder.decode(params.title) }
    if(params.message) { params.message = java.net.URLDecoder.decode(params.message) }

    try
    {
        def dev = findDevByDniOrName(pushoverDevsOnDemand, params.devDniOrName ?: params.device)
        if(!dev) { throw new Exception("no matching device") }
        
        // if the query specified anything other than 'false' for doTake, update the image
        if(![false, "False", "false"].contains(params.doTake)) { dev.take() }
        
        pushoverNotification(dev, params.title ?: getDisplayName(), params.message ?: "New image: ${new Date().toString()}")
    }    
    catch (Exception e)
    {
        return render(contentType: "text/html", data: "Image Server: ${e.message}", status: 200) 
    }
}
