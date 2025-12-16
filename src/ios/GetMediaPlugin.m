#import "GetMediaPlugin.h"
#import <Photos/Photos.h>

@implementation GetMediaPlugin

- (void)getLast:(CDVInvokedUrlCommand*)command {
    NSInteger limit = [command.arguments[0] integerValue];
    PHFetchOptions *options = [[PHFetchOptions alloc] init];
    options.sortDescriptors = @[[NSSortDescriptor sortDescriptorWithKey:@"creationDate" ascending:NO]];
    options.fetchLimit = limit;

    // Photos only
    PHFetchResult<PHAsset *> *results = [PHAsset fetchAssetsWithMediaType:PHAssetMediaTypeImage options:options];
    NSMutableArray *items = [NSMutableArray array];
    dispatch_group_t group = dispatch_group_create();

    for (PHAsset *asset in results) {
        dispatch_group_enter(group);
        NSString *localId = asset.localIdentifier;
        PHContentEditingInputRequestOptions *inputOpts = [[PHContentEditingInputRequestOptions alloc] init];
        [asset requestContentEditingInputWithOptions:inputOpts completionHandler:^(PHContentEditingInput *input, NSDictionary *info) {
            NSURL *url = input.fullSizeImageURL;
            NSString *mime = input.uniformTypeIdentifier ?: @"";
            NSData *data = url ? [NSData dataWithContentsOfURL:url] : nil;
            NSString *b64 = data ? [data base64EncodedStringWithOptions:0] : @"";
            NSDictionary *dict = @{
                @"id": localId,
                @"mimeType": mime,
                @"base64": b64
            };
            [items addObject:dict];
            dispatch_group_leave(group);
        }];
    }

    dispatch_group_notify(group, dispatch_get_main_queue(), ^{
        CDVPluginResult *res = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:items];
        [self.commandDelegate sendPluginResult:res callbackId:command.callbackId];
    });
}

@end
