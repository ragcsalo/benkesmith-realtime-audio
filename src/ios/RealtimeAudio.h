#import <Cordova/CDV.h>

@interface RealtimeAudio : CDVPlugin

- (void)start:(CDVInvokedUrlCommand *)command;
- (void)stop:(CDVInvokedUrlCommand *)command;

@end
