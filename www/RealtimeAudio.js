var exec = require('cordova/exec');

module.exports = {

    start: function (options, onEvent) {
        if (!options || !options.apiKey) {
            throw new Error("apiKey is required");
        }

        exec(
            onEvent,
            null,
            'RealtimeAudio',
            'start',
            [options]
        );
    },

    stop: function () {
        exec(null, null, 'RealtimeAudio', 'stop', []);
    }
};
