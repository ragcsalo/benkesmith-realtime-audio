# benkesmith-realtime-audio

[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A Cordova plugin for **using realtime OpenAI speech recognition** from the phone. Works on both **Android** and **iOS**.

## Features


## Installation

```bash
cordova plugin add https://github.com/ragcsalo/benkesmith-realtime-audio
```

## Usage

```js
RealtimeAudio.start(OPENAI_API_KEY, function (event) {
    const msg = JSON.parse(event);
    console.log(msg);
});

RealtimeAudio.stop();
```

## Platforms

- Android
- iOS

## Android Notes


## iOS Notes
