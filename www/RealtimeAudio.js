var exec = require('cordova/exec');

module.exports = {
    start: function (onAudio) {
        exec(onAudio, null, 'RealtimeAudio', 'start', []);
    },

    stop: function () {
        exec(null, null, 'RealtimeAudio', 'stop', []);
    }
};
