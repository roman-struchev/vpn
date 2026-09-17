import path from 'path';
import { fileURLToPath } from 'url';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
import protobuf from 'protobufjs';
import { logger } from '../utils/logger.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const VLESS_ACCOUNT_TYPE = 'xray.proxy.vless.Account';
const ADD_USER_OPERATION_TYPE = 'xray.app.proxyman.command.AddUserOperation';
const REMOVE_USER_OPERATION_TYPE = 'xray.app.proxyman.command.RemoveUserOperation';
const CALL_TIMEOUT_MS = 3000;

/**
 * Adds/removes VLESS users in a *running* xray through its local HandlerService
 * API (the same dokodemo-door api inbound the stats collector already uses —
 * see config-builder's `api.services`).
 *
 * Why this exists: every ConfigSync used to restart xray, and the client list
 * changes constantly (a new device anywhere, a revoked one, a re-registered
 * app). Each restart drops every established connection on the node, so one
 * user's first connect broke everyone else's session for a moment — and the
 * user's own first request after connecting timed out, because registering
 * their device is itself what triggered the restart.
 *
 * Only the client list can be applied this way; anything structural
 * (transport, REALITY keys, ports — see hasStructuralChanges) still restarts.
 */
export class XrayHandlerApi {
  private client: any = null;
  private addUserOperation: protobuf.Type | null = null;
  private removeUserOperation: protobuf.Type | null = null;
  private vlessAccount: protobuf.Type | null = null;

  constructor(private readonly apiUrl: string = '127.0.0.1:10085') {}

  private init(): boolean {
    if (this.client) return true;
    try {
      const protoPath = path.resolve(__dirname, '../../proto/xray-handler.proto');
      const packageDefinition = protoLoader.loadSync(protoPath, {
        keepCase: true,
        longs: String,
        enums: String,
        defaults: true,
        oneofs: true,
      });
      const pkg: any = grpc.loadPackageDefinition(packageDefinition);
      const HandlerService = pkg.xray.app.proxyman.command.HandlerService;
      this.client = new HandlerService(this.apiUrl, grpc.credentials.createInsecure());

      // protobufjs (a @grpc/proto-loader dependency) is used directly here
      // because the operation and the account inside it are nested,
      // self-serialized TypedMessage payloads — the gRPC stub only serializes
      // the outer AlterInboundRequest.
      const root = protobuf.loadSync(protoPath);
      this.addUserOperation = root.lookupType('xray.app.proxyman.command.AddUserOperation');
      this.removeUserOperation = root.lookupType('xray.app.proxyman.command.RemoveUserOperation');
      this.vlessAccount = root.lookupType('xray.app.proxyman.command.VlessAccount');
      return true;
    } catch (err) {
      logger.warn(`Failed to initialize Xray HandlerService client: ${err}`);
      this.client = null;
      return false;
    }
  }

  async addUser(inboundTag: string, uuid: string, email: string, level: number = 0): Promise<void> {
    if (!this.init()) throw new Error('HandlerService client unavailable');
    const account = this.vlessAccount!.encode(this.vlessAccount!.create({ id: uuid, flow: '' })).finish();
    const operation = this.addUserOperation!
      .encode(
        this.addUserOperation!.create({
          user: { level, email, account: { type: VLESS_ACCOUNT_TYPE, value: account } },
        })
      )
      .finish();
    await this.alterInbound(inboundTag, ADD_USER_OPERATION_TYPE, operation);
  }

  async removeUser(inboundTag: string, email: string): Promise<void> {
    if (!this.init()) throw new Error('HandlerService client unavailable');
    const operation = this.removeUserOperation!.encode(this.removeUserOperation!.create({ email })).finish();
    await this.alterInbound(inboundTag, REMOVE_USER_OPERATION_TYPE, operation);
  }

  private alterInbound(tag: string, operationType: string, operationValue: Uint8Array): Promise<void> {
    return new Promise((resolve, reject) => {
      const deadline = new Date(Date.now() + CALL_TIMEOUT_MS);
      this.client.AlterInbound(
        { tag, operation: { type: operationType, value: operationValue } },
        { deadline },
        (err: grpc.ServiceError | null) => (err ? reject(err) : resolve())
      );
    });
  }

  close(): void {
    try {
      this.client?.close?.();
    } catch {
      // already closed
    }
    this.client = null;
  }
}

export interface XrayClient {
  uuid: string;
  emailTag: string;
}

export interface ClientDiff {
  added: XrayClient[];
  removedEmails: string[];
}

/**
 * Which users have to be added/removed to turn `previous` into `next`. A
 * client whose uuid changed for the same email tag comes back as both a
 * removal and an addition (xray keys users by email, so it must be replaced).
 */
export function computeClientDiff(previous: XrayClient[], next: XrayClient[]): ClientDiff {
  const prevByEmail = new Map(previous.map((c) => [c.emailTag, c.uuid]));
  const nextByEmail = new Map(next.map((c) => [c.emailTag, c.uuid]));

  const added: XrayClient[] = [];
  const removedEmails: string[] = [];

  for (const [emailTag, uuid] of nextByEmail) {
    const prevUuid = prevByEmail.get(emailTag);
    if (prevUuid === undefined) {
      added.push({ uuid, emailTag });
    } else if (prevUuid !== uuid) {
      removedEmails.push(emailTag);
      added.push({ uuid, emailTag });
    }
  }
  for (const emailTag of prevByEmail.keys()) {
    if (!nextByEmail.has(emailTag)) removedEmails.push(emailTag);
  }

  return { added, removedEmails };
}
