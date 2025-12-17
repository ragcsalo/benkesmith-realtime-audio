#import "RealtimeAudio.h"
#import <AVFoundation/AVFoundation.h>

@interface RealtimeAudio ()

@property (nonatomic, strong) AVAudioEngine *audioEngine;
@property (nonatomic, strong) NSURLSessionWebSocketTask *webSocket;
@property (nonatomic, strong) CDVInvokedUrlCommand *activeCommand;
@property (nonatomic, assign) BOOL running;

@end

@implementation RealtimeAudio

#pragma mark - Cordova entry points

- (void)start:(CDVInvokedUrlCommand *)command {

    self.activeCommand = command;

    NSDictionary *opts = [command.arguments firstObject];
    NSString *apiKey = opts[@"apiKey"];
    NSString *instruction = opts[@"instruction"];
    NSString *language = opts[@"language"];

    if (!apiKey) {
        CDVPluginResult *err =
            [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                               messageAsString:@"apiKey is required"];
        [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
        return;
    }

    [self startWebSocketWithApiKey:apiKey
                        instruction:instruction
                            language:language];

    [self startAudioCapture];
}

- (void)stop:(CDVInvokedUrlCommand *)command {

    self.running = NO;

    if (self.audioEngine) {
        [self.audioEngine stop];
        self.audioEngine = nil;
    }

    if (self.webSocket) {
        [self.webSocket cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure
                                     reason:nil];
        self.webSocket = nil;
    }
}

#pragma mark - WebSocket

- (void)startWebSocketWithApiKey:(NSString *)apiKey
                     instruction:(NSString *)instruction
                         language:(NSString *)language {

    NSURL *url =
        [NSURL URLWithString:
         @"wss://api.openai.com/v1/realtime?model=gpt-realtime-mini"];

    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
    [req setValue:[NSString stringWithFormat:@"Bearer %@", apiKey]
 forHTTPHeaderField:@"Authorization"];
    [req setValue:@"realtime=v1" forHTTPHeaderField:@"OpenAI-Beta"];

    NSURLSession *session =
        [NSURLSession sessionWithConfiguration:
         [NSURLSessionConfiguration defaultSessionConfiguration]];

    self.webSocket = [session webSocketTaskWithRequest:req];
    [self.webSocket resume];

    [self sendSessionUpdateWithInstruction:instruction language:language];
    [self listenForMessages];
}

- (void)sendSessionUpdateWithInstruction:(NSString *)instruction
                                language:(NSString *)language {

    NSMutableDictionary *transcription = [@{
        @"model": @"gpt-4o-transcribe"
    } mutableCopy];

    if (language.length > 0) {
        transcription[@"language"] = language;
    }

    NSMutableDictionary *session = [@{
        @"modalities": @[ @"text" ],
        @"input_audio_transcription": transcription,
        @"turn_detection": @{
            @"type": @"server_vad",
            @"create_response": @NO
        }
    } mutableCopy];

    if (instruction.length > 0) {
        session[@"instructions"] =
            [NSString stringWithFormat:
             @"You are a speech information extractor. %@ "
             "Return ONLY JSON in the form {\"response\":\"VALUE\"} "
             "or {\"response\":\"???\"}. No explanation.",
             instruction];
    }

    NSDictionary *payload = @{
        @"type": @"session.update",
        @"session": session
    };

    [self sendJSON:payload];
}

#pragma mark - Audio capture

- (void)startAudioCapture {

    self.running = YES;

    AVAudioFormat *format =
        [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatInt16
                                         sampleRate:16000
                                           channels:1
                                        interleaved:YES];

    self.audioEngine = [[AVAudioEngine alloc] init];

    AVAudioInputNode *input = self.audioEngine.inputNode;

    [input installTapOnBus:0
                bufferSize:320
                    format:format
                     block:^(AVAudioPCMBuffer *buffer, AVAudioTime *time) {

        if (!self.running || !self.webSocket) return;

        NSData *pcmData =
            [NSData dataWithBytes:buffer.int16ChannelData[0]
                           length:buffer.frameLength * 2];

        NSString *b64 =
            [pcmData base64EncodedStringWithOptions:0];

        NSDictionary *msg = @{
            @"type": @"input_audio_buffer.append",
            @"audio": b64
        };

        [self sendJSON:msg];
    }];

    [self.audioEngine prepare];
    [self.audioEngine startAndReturnError:nil];
}

#pragma mark - WebSocket receive

- (void)listenForMessages {

    if (!self.webSocket) return;

    __weak typeof(self) weakSelf = self;

    [self.webSocket receiveMessageWithCompletionHandler:
     ^(NSURLSessionWebSocketMessage *message, NSError *error) {

        if (error) return;

        if (message.type == NSURLSessionWebSocketMessageTypeString) {

            CDVPluginResult *res =
                [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                   messageAsString:message.string];
            [res setKeepCallbackAsBool:YES];

            [weakSelf.commandDelegate
             sendPluginResult:res
             callbackId:weakSelf.activeCommand.callbackId];

            // trigger extraction after transcription completes
            if ([message.string containsString:
                 @"conversation.item.input_audio_transcription.completed"]) {

                NSDictionary *req = @{
                    @"type": @"response.create",
                    @"response": @{ @"modalities": @[ @"text" ] }
                };
                [weakSelf sendJSON:req];
            }
        }

        [weakSelf listenForMessages];
    }];
}

#pragma mark - Utilities

- (void)sendJSON:(NSDictionary *)obj {

    NSData *data =
        [NSJSONSerialization dataWithJSONObject:obj
                                        options:0
                                          error:nil];

    NSString *json =
        [[NSString alloc] initWithData:data
                              encoding:NSUTF8StringEncoding];

    NSURLSessionWebSocketMessage *msg =
        [[NSURLSessionWebSocketMessage alloc] initWithString:json];

    [self.webSocket sendMessage:msg completionHandler:nil];
}

@end
