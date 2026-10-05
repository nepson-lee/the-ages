import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { ClientMessageSchema, ServerMessageSchema, type ClientMessage, type ServerMessage } from "../gen/theages/v1/game_pb";

export interface ConnectionHandlers {
  onMessage(msg: ServerMessage): void;
  onClose(reason: string): void;
}

/** 與遊戲伺服器的 WebSocket 連線（Protobuf 二進位訊息）。 */
export class GameConnection {
  private readonly ws: WebSocket;

  constructor(token: string, handlers: ConnectionHandlers) {
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    this.ws = new WebSocket(`${scheme}://${location.host}/ws?token=${encodeURIComponent(token)}`);
    this.ws.binaryType = "arraybuffer";
    this.ws.onmessage = (ev) => {
      handlers.onMessage(fromBinary(ServerMessageSchema, new Uint8Array(ev.data as ArrayBuffer)));
    };
    this.ws.onclose = (ev) => handlers.onClose(ev.reason || "與伺服器的連線中斷了");
  }

  moveTo(x: number, z: number): void {
    this.send(create(ClientMessageSchema, { payload: { case: "moveTo", value: { target: { x, z } } } }));
  }

  attack(targetId: number): void {
    this.send(create(ClientMessageSchema, { payload: { case: "attack", value: { targetId } } }));
  }

  command(text: string): void {
    this.send(create(ClientMessageSchema, { payload: { case: "command", value: { text } } }));
  }

  close(): void {
    this.ws.close();
  }

  private send(msg: ClientMessage): void {
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(toBinary(ClientMessageSchema, msg));
    }
  }
}
