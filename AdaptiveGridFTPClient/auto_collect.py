import os

#src = "gsiftp://oasis-dm.sdsc.xsede.org//oasis/scratch-comet/earslan/temp_project/1G-"
base_format = "timeout 65 mvn exec:java -Dexec.args=\"src/main/resources/config.cfg -s {0} -d {1} -maxcc {2}\""
src_format = "ftp://<source_ip>:50505/data/arif/{0}/"
dest_format = "ftp://<dest_ip>:50505/data/arif/{0}/"
ccs = [2,4,8,16,32]
filesizes = ["large", "medium", "small"]

count = 0
while True:
    count += 1 
    for cc in ccs:
        for filesize in filesizes:
            src = src_format.format(filesize)
            dest = dest_format.format(filesize)
            command = base_format.format(src,dest,cc)
            print(count, command)
            data = os.popen(command).read()
            rename = "mv inst-throughput.txt data/inst-throughput-cc-{0}-fs-{1}-iter-{2}.txt".format(cc, filesize, count)
            data = os.popen(rename).read()

