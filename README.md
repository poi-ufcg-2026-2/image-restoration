# Image Restoration — backend CPU-bound

Backend HTTP que restaura imagens borradas usando o algoritmo de **Richardson-Lucy**
(versão serial do [Lab2 de Programação Concorrente](https://github.com/giovannifs/fpc/tree/master/2025.1/Lab2)).

Cada requisição executa convoluções sucessivas em ponto flutuante e praticamente não faz I/O,
o que caracteriza o perfil **CPU-bound**.

Requer apenas o JDK 17+ (`sudo apt install openjdk-21-jdk-headless`). Não usa bibliotecas externas.

## Executar

```bash
./build.sh
./run.sh            # porta 8080
```

Variáveis opcionais: `PORT` (padrão 8080) e `THREADS` (requisições processadas em paralelo; padrão = nº de CPUs).

## Endpoint

`POST /restore` — restaura a imagem enviada no corpo (até 10 MB) e devolve um PNG com o cabeçalho
`X-Processing-Time-Ms`. Outros métodos recebem `405`.

```bash
curl -o restaurada.png --data-binary @images/lena-borrada.jpg http://localhost:8080/restore
```

## Logs

Cada requisição gera uma linha com timestamp, nível, thread e métricas no formato `chave=valor`:

```
2026-10-13 14:02:11.482 INFO  [pool-1-thread-3] req=7 POST /restore client=127.0.0.1 status=200 img=512x512 in_bytes=48213 out_bytes=402117 decode_ms=6 restore_ms=1830 encode_ms=41 total_ms=1878
```

Requisições rejeitadas (400, 405, 413) são registradas como `WARN` com o motivo; falhas internas como `ERROR` com stack trace.
