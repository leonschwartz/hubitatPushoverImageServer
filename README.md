# Hubitat Pushover Image Server
Simple image server to send images from cameras to Pushover devices

Code is a modified (and simplified) version of https://github.com/tomwpublic/hubitat_imageServer/blob/main/imageServerApp (removing functionality that is not related to pushing images to Pushover devices).  The actual sending the push logic has been removed and is instead in a modified Pushover driver (see: https://github.com/leonschwartz/hubitatPushoverWithImage).
