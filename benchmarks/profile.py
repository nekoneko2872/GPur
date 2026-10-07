"""Read a locally exported spark execution profile, without uploading it.

Wire field identifiers follow spark's official SamplerData schema:
https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto
Only stack/timing fields are decoded; configuration, player data and unrelated
metadata are deliberately omitted from the portable report. Requires protobuf.
"""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path


def schema():
    from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
    file = descriptor_pb2.FileDescriptorProto(name='gpur_spark_subset.proto', package='gpur_spark', syntax='proto3')
    # Scalar type numbers are the stable Protocol Buffers FieldDescriptor enum.
    definitions = {
        'Metadata': [('start_time', 2, 3, False, None), ('interval', 3, 5, False, None),
                     ('end_time', 11, 3, False, None)],
        'Node': [('children', 2, 11, True, 'Node'), ('class_name', 3, 9, False, None),
                 ('method_name', 4, 9, False, None), ('times', 8, 1, True, None),
                 ('children_refs', 9, 5, True, None)],
        'Thread': [('name', 1, 9, False, None), ('children', 3, 11, True, 'Node'),
                   ('times', 4, 1, True, None), ('children_refs', 5, 5, True, None)],
        'Data': [('metadata', 1, 11, False, 'Metadata'), ('threads', 2, 11, True, 'Thread')]
    }
    for name, fields in definitions.items():
        message = file.message_type.add(name=name)
        for field_name, number, kind, repeated, target in fields:
            field = message.field.add(name=field_name, number=number, type=kind, label=3 if repeated else 1)
            if target:
                field.type_name = '.gpur_spark.' + target
    pool = descriptor_pool.DescriptorPool()
    pool.Add(file)
    return message_factory.GetMessageClass(pool.FindMessageTypeByName('gpur_spark.Data'))


def reduce_thread(thread):
    inclusive, own = defaultdict(float), defaultdict(float)
    nodes = thread.children

    def visit(node):
        value = sum(node.times)
        children = [nodes[reference] for reference in node.children_refs] if node.children_refs else node.children
        child_time = sum(sum(child.times) for child in children)
        name = node.class_name + '.' + node.method_name
        inclusive[name] += value
        own[name] += max(0, value - child_time)
        return children

    if thread.children_refs:
        # Current format stores every node once in one flat array per thread.
        for node in nodes:
            visit(node)
    else:
        pending = list(nodes)
        while pending:
            pending.extend(visit(pending.pop()))
    total = sum(thread.times)
    def ranked(values):
        return [{'method': name, 'sampled_ms': round(value, 3),
                 'percent_of_thread_samples': round(value / total * 100, 3) if total else None}
                for name, value in sorted(values.items(), key=lambda item: item[1], reverse=True)[:30]]
    return {'thread': thread.name, 'sampled_ms': total, 'inclusive_methods': ranked(inclusive),
            'self_methods': ranked(own)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('profile', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    payload = args.profile.read_bytes()
    if payload.startswith(b'\x1f\x8b'):
        payload = gzip.decompress(payload)
    data = schema().FromString(payload)
    if not data.threads:
        raise ValueError('No spark execution threads decoded')
    report = {'profile': args.profile.name, 'start_epoch_ms': data.metadata.start_time,
              'end_epoch_ms': data.metadata.end_time, 'sampler_interval': data.metadata.interval,
              'note': 'Sampled stack time includes waits/sleep. Inclusive methods overlap; percentages must not be added. Whole-run profile includes setup.',
              'threads': [reduce_thread(thread) for thread in data.threads]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    for thread in report['threads']:
        if 'Server thread' in thread['thread']:
            print(json.dumps(thread, indent=2))


if __name__ == '__main__':
    main()
