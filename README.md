# voluble

When working with Apache Kafka, you often find yourself wanting to continuously populate your topics with something that approximates the shape of your production data. Voluble solves that problem. The primary things it supports are:

- Creating realistic data by integrating with [Java Faker](https://github.com/DiUS/java-faker)
- Cross-topic relationships
- Populating both keys and values of records
- Making both primitive and complex values
- Continous streaming of new data
- Tombstoning

Voluble ships as a [Kafka connector](https://docs.confluent.io/current/connect/index.html) to make it easy to scale and change serialization formats. You can use Kafka Connect through its REST API or integrated with [ksqlDB](http://ksqldb.io/). In this guide, I demonstrate using the latter, but the configuration is the same for both. I leave out Connect specific configuration like serializers and tasks that need to be configured for any connector.

## Simple example

Let's look at a simple example of Voluble in action. Let's say that you want to generate events for a topic named "users". Suppose that you're expecting the key of each event to be a UUID and the value to be a map with a few different attributes. You can express that in Voluble like so:

```sql
CREATE SOURCE CONNECTOR s WITH (
  'connector.class' = 'io.mdrogalis.voluble.VolubleSourceConnector',
  'genkp.users.with' = '#{Internet.uuid}',
  'genv.users.jobTitle.with' = '#{Name.title.job}',
  'genv.users.country.with' = '#{Address.country_code}',
  'genv.users.creditCard.with' = '#{Finance.credit_card}'
);
```

There are two main directives in the above configuration: `genkp` and `genv`, which stand for "generate key primitive" and "generate value" respectively. The value for each of these properties is a Java Faker expression. When you run this connector, you'll get data like the following:

```json
{
    "key": "e74deee9-4746-4b4a-9525-ed28453b58e6",
    "value": {
        "jobTitle": "Facilitator",
        "country": "ZA",
        "creditCard" "4061-1479-1219-8683"
    }
}
```

## Usage

All configuration for Voluble is done through properties that are passed to the connector.

### Generating data

Generating events with Voluble basically take the form of:

```
'<directive>.<topic>.[attribute?].[qualifier?].<generator>' = 'expression'
```

Let's break down what exactly that means.

#### Directives

There are 4 top-level directives for generating a single Kafka record: `genk`, `genkp`, `genv`, and `genv`. Those stand for generate key, generate key primitive, generate value, and generate value primitive. Non-primitive, or complex, generators are for generating maps of values where the keys are named. Key and value generators can be used together in any combination, but primitive and complex generators for the same part of a record, like the key, are mutually exclusive.

#### Topic and attribute

Topic is the topic that Voluble will generate data to for this expression. If you're generating a complex value, you'll also need to specify an attribute, which is just the key that the expression will be generated for. For example, you might specify that an attribute is `"name"`. When you generate events, you'll get maps with a `"name"` key in them.

#### Generator

There are two types of generators: `with` and `matching`. `with` takes a Java Faker expression as its value and generates data irrespective of any other topics. `matching` allows you to generate data that has already been generated in another topic. This is useful when your real data has relationships and might be joined downstream. The value for a `matching` generator takes the form of: `<topic>.[key|value].[attribute?]`. This syntax let's you target data in another topic in either the key or value of a record. If it's a complex data structure, you can get a single key out of it.

#### Qualifier

Qualifiers let you control how generators work. Right now there is only one qualifier: `sometimes`. Sometimes you want to generate data that matches another topic, but not always. This is useful if you're modeling a single topic who's key's represent mutability. Or maybe you want to model a stream/stream join. `sometimes` allows you to control the probability that Voluble will generate a matching value versus a brand new one. You'd use it roughly like the following: `genv.users.team.sometimes.matching` = `team.key.name`, `genv.users.team.sometimes.with` = '#{Team.name}'. When you use `sometimes`, you need to specify both `matching` and `with`. By default there is now a `0.1` probability rate of matching, instead of `1`. You can control the probability to suit your circumstances (see the reference section).

#### Expressions

When a `with` generator is used, the value is passed verbatim to Java Faker to create a value. Java Faker has a huge number of categories that it can generate data for. Just check out the project to get a sense for what you can do. Under the covers, the [`expression` method](https://github.com/DiUS/java-faker/blob/7ac7e53aa2e9a3d39c1e663ddf62b1feb625b060/src/main/java/com/github/javafaker/Faker.java#L636-L654) of Faker is being invoked to dynamically create data without going through its Java classes.

If you get stuck generating something that you want, just instantiate Faker directly in a Java program and call `faker.expression()` until you get the thing you're looking for.

## Installation

I haven't published this to Maven or Confluent Hub yet, so just download the repo and build it locally. Install the uberjar on the classpath of Connect and you're good to go.

## More examples

For concision, I just list out the relevant configuration.

**A primitive key and primitive value**

```
'genkp.people.with' = '#{Internet.uuid}'
'genvp.people.with' = '#{Name.full_name}'
```

**A primitive key and complex value**

```
'genkp.people.with' = '#{Internet.uuid}'
'genv.people.name.with' = '#{Name.full_name}'
'genv.people.bloodType.with' = '#{Name.blood_group}'
```

**A complex key and complex value**

```
'genk.people.id.with' = '#{Internet.uuid}'
'genk.people.avatar.with' = '#{Internet.avatar}'
'genv.people.name.with' = '#{Name.full_name}'
'genv.people.bloodType.with' = '#{Name.blood_group}'
```

**A value with no key**

Omitting any generator for a key simply makes it `null`.

```
'genv.people.name.with' = '#{Name.full_name}'
```

**A topic that gets part of its value from another topic's primitive key**

`key` can instead be `value` to reference that part of the record.

```
'genkp.users.with' = '#{Name.full_name}'
'genvp.users.with' = '#{Name.blood_group}'

'genkp.publications.matching' = 'users.key'
'genv.publications.title.with' = '#{Book.title}'
```

**A topic that gets part of its value from another topic's complex key**

```
'genk.users.name.with' = '#{Name.full_name}'
'genvp.users.with' = '#{Name.blood_group}'

'genkp.publications.matching' = 'users.key.name'
'genv.publications.title.with' = '#{Book.title}'
```

**A topic with keys that update once in a while**

Notice that a record can match against itself. Useful for representing updates in a topic.

```
'genkp.users.sometimes.with' = '#{Name.full_name}'
'genkp.users.sometimes.matching' = 'users.key'
'genv.users.bloodType.with' = '#{Name.blood_group}'
```

**Two topics that sometimes share a key in common**

Useful for modeling stream/stream joins.

```
'genkp.teamA.sometimes.with' = '#{Team.name}'
'genkp.teamA.sometimes.matching' = 'teamB.key'

'genkp.teamB.sometimes.with' = '#{Team.name}'
'genkp.teamB.sometimes.matching' = 'teamA.key'
```

## Reference

Voluble has a few other knobs for controlling useful properties. Some properties can be defined at the attribute, topic, and global level. The most granular scope takes precedence (topic over global, etc).

### Matching probability

When you use the `sometimes` qualifier, the rate of matching is reduced from a certainty (`1`) to `0.1`. You can control this probability at both the global and attribute level. To alter the matching rate global, configure `global.matching.rate` to be a value between `0` and `1`. To configure it at the attribute level, configure it roughly like `attrk.<topic>.<attribute>.matching.rate`. Attribute configuration mirrors generator configuration: `attrk`, `attrkp`, `attrv`, and `attrvp` the same as generators.

### Tombstoning

In Kafka, a key with a `null` value is called a tombstone. It conventionally represents the deletion of a key in a topic. Sometimes it's useful to generate tombstones. By default Voluble won't do this, but you can turn it on per topic with `topic.<topic>.tombstone.rate` = `p`. `p` is a value between `0` and `1`, and it represents the probability that a tombstone will be generated instead of a value.

### Null value rates

Sometimes when you're generating complex values, you might want the value for a key to be null. By default Voluble doesn't do this, but you can turn configure it at the attribute level like so: `attrv.<topic>.<attribute>.null.rate` = `p`.

### History capacity

To perform `matching` expressions, Voluble needs to keep the history of previously generated records for each topic. By default, only the most recent `1,000,000` records are kept per topic. You can override this per topic with `topic.<topic>.history.records.max` = `n`, or globally with `global.history.records.max` = `n`.

## Limitations

- There is no built-in support for multi-schema topics. If you want to do this, just create multiple instances of Voluble with different generator configurations.
- There is not yet support for deeply nested complex keys or values. That is, it can only generate maps with a single layer of keys.
- Voluble doesn't yet validate that you're not asking for something impossible, like a circular dependency. If it'=s internal state machine can't advance and generate a new event after `100` iterations, the connector will permanently abort.

If Voluble doesn't do something you want it to, feel free to open and issue or submit a patch.

## License

Copyright © 2020 Michael Drogalis

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.