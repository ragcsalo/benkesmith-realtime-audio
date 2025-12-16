var exec = require('cordova/exec');

module.exports = {
    start: function (apiKey, onAudio) {
        exec(onAudio, null, 'RealtimeAudio', 'start', [apiKey]);
    },

    stop: function () {
        exec(null, null, 'RealtimeAudio', 'stop', []);
    }
};
